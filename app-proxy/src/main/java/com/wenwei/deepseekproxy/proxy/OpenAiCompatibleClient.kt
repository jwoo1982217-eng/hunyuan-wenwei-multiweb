package com.wenwei.deepseekproxy.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class ApiTestResult(
    val success: Boolean,
    val message: String,
    val chatModels: List<String> = emptyList(),
    val imageModels: List<String> = emptyList(),
    val videoModels: List<String> = emptyList(),
    val latencyMs: Long = 0L
) {
    val models: List<String> get() = (chatModels + imageModels + videoModels).distinct()
}

object OpenAiCompatibleClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    // Agnes Image 是同步接口，文档建议客户端超时 60s-360s；大图/多图合成+b64 返回可能超 180s，
    // 用独立的长超时 client，避免被 180s 掐断报超时。
    private val longClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(360, TimeUnit.SECONDS).build()

    internal fun apiRoot(address: String): String {
        var value = address.trim().trimEnd('/')
        listOf("/chat/completions", "/images/generations", "/models").firstOrNull {
            value.endsWith(it, ignoreCase = true)
        }?.let { suffix -> value = value.dropLast(suffix.length).trimEnd('/') }
        // 兼容旧版 APK 逻辑：baseUrl 已带 /v1~/v6 版本号(如智谱 https://open.bigmodel.cn/api/paas/v4)
        // 时不再追加 /v1，否则会拼出 /v4/v1/... 导致 404。
        val hasVersionSuffix = (1..6).any { value.endsWith("/v$it", ignoreCase = true) }
        return if (hasVersionSuffix) value else "$value/v1"
    }

    internal fun endpoint(config: ApiProviderConfig, path: String): String {
        return "${apiRoot(config.normalizedBaseUrl())}/$path"
    }

    private fun Request.Builder.authorize(config: ApiProviderConfig): Request.Builder = apply {
        if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
    }

    suspend fun test(config: ApiProviderConfig): ApiTestResult = withContext(Dispatchers.IO) {
        if (CloudflareWorkersAiClient.matches(config)) {
            return@withContext CloudflareWorkersAiClient.test(config)
        }
        val startedAt = System.nanoTime()
        runCatching {
            client.newCall(Request.Builder().url(endpoint(config, "models")).authorize(config).get().build())
                .execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return@use ApiTestResult(
                        false,
                        "HTTP ${response.code}: ${body.take(180)}",
                        latencyMs = (System.nanoTime() - startedAt) / 1_000_000
                    )
                    val parsed = normalizeProviderModels(config, parseModelList(body))
                    parsed.copy(
                        message = "${parsed.message}，${(System.nanoTime() - startedAt) / 1_000_000}ms",
                        latencyMs = (System.nanoTime() - startedAt) / 1_000_000
                    )
                }
        }.getOrElse { ApiTestResult(
            false, "网络异常: ${it.message ?: it.javaClass.simpleName}",
            latencyMs = (System.nanoTime() - startedAt) / 1_000_000
        ) }
    }

    internal fun parseModelList(body: String): ApiTestResult {
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
        val chat = mutableListOf<String>()
        val images = mutableListOf<String>()
        val videos = mutableListOf<String>()
        for (element in data) {
            val item = runCatching { element.jsonObject }.getOrNull() ?: continue
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (id.isBlank()) continue
            val metadata = element.toString().lowercase()
            // OpenRouter 风格中转（商汤 token.sensenova.cn 等）用 output_modalities 声明输出模态，
            // 生图模型名（sensenova-u1.5-lite / u1-fast）既不含 image 关键词也没有 type 字段，
            // 只能靠该字段识别；input_modalities 不看（那是识图聊天，不是生图）。
            val outputModalities = runCatching {
                item["output_modalities"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrNull().orEmpty()
            val explicitImage = listOf("image_generation", "image-generation", "text_to_image", "text-to-image")
                .any(metadata::contains) || outputModalities.any { it.contains("image", ignoreCase = true) } ||
                item["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .contains("image", ignoreCase = true)
            val explicitVideo = listOf("video_generation", "video-generation", "text_to_video", "text-to-video")
                .any(metadata::contains) || outputModalities.any { it.contains("video", ignoreCase = true) } ||
                item["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .contains("video", ignoreCase = true)
            val explicitChat = listOf("chat.completions", "chat_completion", "text_generation", "text-generation")
                .any(metadata::contains)
            // 拆分规则：id 里含视频关键词（video/sora/kling/可灵/wan/t2v…）一律归视频，
            // 含图片关键词（image/dall/flux/sdxl/draw/生图…）归生图，其余归聊天。
            val videoById = VIDEO_MODEL_ID.containsMatchIn(id) || id.contains("video", ignoreCase = true)
            val imageById = IMAGE_MODEL_ID.containsMatchIn(id) || id.contains("image", ignoreCase = true)
            if (explicitVideo || videoById) videos += id
            if (explicitImage || imageById) images += id
            if (explicitChat || (!explicitVideo && !videoById && !explicitImage && !imageById)) chat += id
        }
        val allCount = (chat + images + videos).distinct().size
        return ApiTestResult(
            success = true,
            message = "连接成功，发现 $allCount 个模型（聊天 ${chat.distinct().size}，生图 ${images.distinct().size}，视频 ${videos.distinct().size}）",
            chatModels = chat.distinct(),
            imageModels = images.distinct(),
            videoModels = videos.distinct()
        )
    }

    internal fun normalizeProviderModels(config: ApiProviderConfig, result: ApiTestResult): ApiTestResult {
        // 智谱 /models 只返回 GLM 聊天模型，CogView/CogVideoX 从不进列表（2026-09-12 实测），
        // 这里并入内置清单；同步"只增不删"，不影响手填模型。
        // 仅当该供应商对应列表为空（新条目）时注入：非空说明用户在自管列表，
        // 再注入会把用户删掉的模型加回来（同步只增不删 + 注入 = 删除被回滚的教训）。
        if (config.baseUrl.contains("bigmodel.cn", ignoreCase = true)) {
            val images = if (config.imageModels.isEmpty()) (result.imageModels + "cogview-3-flash").distinct() else result.imageModels
            val videos = if (config.videoModels.isEmpty()) (result.videoModels + listOf("cogvideox-flash", "cogvideox-3")).distinct() else result.videoModels
            val allCount = (result.chatModels + images + videos).distinct().size
            return result.copy(
                message = "连接成功，发现 $allCount 个模型（聊天 ${result.chatModels.size}，生图 ${images.size}，视频 ${videos.size}）",
                imageModels = images,
                videoModels = videos
            )
        }
        if (!HiApiImageClient.matches(config)) return result
        val current = result.imageModels
            .filterNot { it.equals("gpt-image-1", true) || it.equals("gpt-image-1.5", true) }
            .filterNot { it.contains("/image-to-image", true) }
            .toMutableList()
        HiApiImageClient.textToImageModels.forEach { model ->
            if (current.none { it.equals(model, true) }) current += model
        }
        val allCount = (result.chatModels + current).distinct().size
        return result.copy(
            message = "连接成功，发现 $allCount 个模型（聊天 ${result.chatModels.size}，生图 ${current.distinct().size}）",
            imageModels = current.distinct()
        )
    }

    private val IMAGE_MODEL_ID = Regex(
        "(^|[-_./])(gpt[-_]?image|dall[-_]?e|flux|sdxl|stable[-_]?diffusion|imagen|seedream|recraft|ideogram|kolors|cogview|nano[-_]?banana|image[-_]?gen)([-_./]|$)",
        RegexOption.IGNORE_CASE
    )

    private val VIDEO_MODEL_ID = Regex(
        "(^|[-_./])(video|sora|kling|wanx|wan[-_]?[0-9]|veo|runway|pika|gen[-_]?video|text[-_]?to[-_]?video|t2v|v2v|animate|mochi|cogvideo)([-_./]|$)",
        RegexOption.IGNORE_CASE
    )

    fun executeChat(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): Response {
        val body = JSONObject(rawRequest).put("model", upstreamModel).toString()
        return client.newCall(Request.Builder().url(endpoint(config, "chat/completions"))
            .authorize(config).header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType())).build()).execute()
    }

    private fun senseNovaImage(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): Response {
        val body = JSONObject(rawRequest).put("model", upstreamModel).put("watermark", false)
        var path = "images/generations"
        val refs = body.optJSONArray("image_data")
        if (refs != null && refs.length() > 0) {
            // 官方图片编辑接口（POST /v1/images/edits，JSON，同步）：images 元素必须是
            // {"image_url": dataURL 或公网URL} 对象，裸字符串数组会 400 invalid arguments
            path = "images/edits"
            val images = org.json.JSONArray()
            for (i in 0 until refs.length()) {
                val item = refs.optJSONObject(i) ?: continue
                val data = item.optString("data", "")
                if (data.isBlank()) continue
                val dataUrl = if (data.startsWith("data:")) data else run {
                    val mime = item.optString("mime", "image/jpeg").ifBlank { "image/jpeg" }
                    "data:$mime;base64,$data"
                }
                images.put(JSONObject().put("image_url", dataUrl))
            }
            body.put("images", images)
            body.remove("image_data")
            body.remove("image")
            body.remove("image_url")
            body.put("response_format", "b64_json")
        }
        return client.newCall(
            Request.Builder().url(endpoint(config, path))
                .authorize(config).header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        ).execute()
    }

    fun executeVideo(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): Response {
        val jsonBody = JSONObject(rawRequest).apply {
            put("model", upstreamModel)
            val imgArr = optJSONArray("image_data")
            val imgCount = imgArr?.length() ?: 0

            // 提取 UI 透传的视频参数，一律移除避免残留进上游请求体
            val durationStr = optString("duration_str", "Auto").trim()
            val resolutionStr = optString("resolution_str", "Auto").trim()
            val ratioStr = optString("ratio_str", "Auto").trim()
            val fpsStr = optString("fps_str", "Auto").trim()
            val audioPref = optString("audio_pref", "关").trim()
            val watermarkPref = optString("watermark_pref", "开").trim()
            remove("duration_str"); remove("resolution_str"); remove("ratio_str"); remove("fps_str")
            remove("audio_pref"); remove("watermark_pref")

            // 1. 智谱 (bigmodel.cn / cogvideox) 适配：
            // 重要：cogvideox-flash (官方 cURL 示例有 quality/with_audio/size/fps/duration)
            // 重要：cogvideox-2 / cogvideox-3 不接受 with_audio 字段(实测 400: not allowed)
            if (config.baseUrl.contains("bigmodel.cn", ignoreCase = true)) {
                val isFlash = upstreamModel.contains("flash", ignoreCase = true) ||
                    upstreamModel.contains("v2.0", ignoreCase = true)
                val isCogVideoX3 = upstreamModel.contains("cogvideox-3", ignoreCase = true) ||
                    upstreamModel.contains("cogvideox_3", ignoreCase = true)

                // size 解析：图生视频时若不指定 size，智谱自适应原图；带 size 才校验
                // 优先用 UI 透传的比例映射；文生视频（无图）才传 size
                if (imgCount == 0) {
                    val zhipuSize = when {
                        ratioStr == "16:9" || resolutionStr.contains("横", ignoreCase = true) -> "1920x1080"
                        ratioStr == "1:1" -> "1024x1024"
                        ratioStr == "9:16" || ratioStr == "Auto" || ratioStr.isBlank() -> "720x1280"
                        else -> "1280x960" // 接近 4:3 兜底
                    }
                    put("size", zhipuSize)
                }

                val durationSec = durationStr.removeSuffix("s").removeSuffix("S").toIntOrNull() ?: 5
                if (isFlash || isCogVideoX3) put("duration", if (durationSec >= 10) 10 else 5)

                val fpsVal = fpsStr.removeSuffix("fps").removeSuffix("FPS").toIntOrNull() ?: 30
                put("fps", if (fpsVal >= 60) 60 else 30)

                // with_audio 仅 flash/v2 支持；cogvideox-3 不接受
                if (isFlash && !has("with_audio")) put("with_audio", false)

                // cogvideox-3 必填 quality 字段；flash 默认 speed；UI 暂时没透传则用 speed
                if (isCogVideoX3 && !has("quality")) put("quality", "speed")

                if (imgCount > 0) {
                    if (imgCount == 1) {
                        val item = imgArr?.optJSONObject(0)
                        if (item != null) {
                            val data = item.optString("data", "")
                            val mime = item.optString("mime", "image/jpeg").ifBlank { "image/jpeg" }
                            if (data.isNotBlank()) {
                                val payload = if (data.contains("base64,")) data.substringAfter("base64,") else data
                                put("image_url", "data:$mime;base64,$payload")
                            }
                        }
                    } else {
                        // 首尾帧生视频（2张图：首帧 + 尾帧）
                        val urls = org.json.JSONArray()
                        for (i in 0 until imgCount) {
                            val item = imgArr?.optJSONObject(i) ?: continue
                            val data = item.optString("data", "")
                            val mime = item.optString("mime", "image/jpeg").ifBlank { "image/jpeg" }
                            if (data.isNotBlank()) {
                                val payload = if (data.contains("base64,")) data.substringAfter("base64,") else data
                                urls.put("data:$mime;base64,$payload")
                            }
                        }
                        if (urls.length() > 0) put("image_url", urls)
                    }
                }
                remove("image_data")
            }

            // 2. Agnes Video 适配（v2.0 vs 2.5 / 2.5-flash）：
            if (config.baseUrl.contains("agnes", ignoreCase = true) ||
                upstreamModel.contains("agnes", ignoreCase = true)) {
                val isV25 = upstreamModel.contains("2.5", ignoreCase = true)
                val isFlash = upstreamModel.contains("flash", ignoreCase = true)

                // 画幅比例：优先 9:16 竖屏
                val agnesRatio = when (ratioStr) {
                    "16:9", "4:3", "1:1", "3:4", "21:9" -> ratioStr
                    "9:16" -> "9:16"
                    else -> "9:16" // 优先竖屏
                }
                put("aspect_ratio", agnesRatio)

                val durationSec = durationStr.removeSuffix("s").removeSuffix("S").toIntOrNull() ?: 5
                put("seconds", durationSec.toString())

                val agnesSize = if (isFlash) "720P" else when (resolutionStr) {
                    "1080p", "1080P", "2k", "2K" -> "1080P"
                    "960p", "960P" -> "960P"
                    else -> "720P"
                }
                put("size", agnesSize)

                if (isV25) {
                    // 2.5 / 2.5-flash:
                    // 文生: mode=text
                    // 首尾帧(2张图): mode=keyframe, first_frame, last_frame
                    // 图生/参考图(1张或多张): mode=reference, images=[...]
                    when (imgCount) {
                        0 -> {
                            put("mode", "text")
                        }
                        2 -> {
                            put("mode", "keyframe")
                            val img1 = imgArr?.optJSONObject(0)?.optString("data", "") ?: ""
                            val mime1 = imgArr?.optJSONObject(0)?.optString("mime", "image/jpeg") ?: "image/jpeg"
                            val img2 = imgArr?.optJSONObject(1)?.optString("data", "") ?: ""
                            val mime2 = imgArr?.optJSONObject(1)?.optString("mime", "image/jpeg") ?: "image/jpeg"
                            if (img1.isNotBlank()) {
                                val p1 = if (img1.contains("base64,")) img1.substringAfter("base64,") else img1
                                put("first_frame", "data:$mime1;base64,$p1")
                            }
                            if (img2.isNotBlank()) {
                                val p2 = if (img2.contains("base64,")) img2.substringAfter("base64,") else img2
                                put("last_frame", "data:$mime2;base64,$p2")
                            }
                        }
                        else -> {
                            put("mode", "reference")
                            val images = org.json.JSONArray()
                            for (i in 0 until imgCount) {
                                val item = imgArr?.optJSONObject(i) ?: continue
                                val data = item.optString("data", "")
                                val mime = item.optString("mime", "image/jpeg").ifBlank { "image/jpeg" }
                                if (data.isNotBlank()) {
                                    val payload = if (data.contains("base64,")) data.substringAfter("base64,") else data
                                    images.put("data:$mime;base64,$payload")
                                }
                            }
                            if (images.length() > 0) put("images", images)
                        }
                    }
                } else {
                    // v2.0 系列:
                    // 文生: mode=ti2vid
                    // 单图生视频: mode=ti2vid, image="data:..." (注意：单图必须是 ti2vid + image 字段)
                    // 多图/首尾帧关键帧: mode=keyframes, extra_body.image=[img1, img2...], extra_body.mode=keyframes
                    when (imgCount) {
                        0 -> {
                            put("mode", "ti2vid")
                        }
                        1 -> {
                            put("mode", "ti2vid")
                            val item = imgArr?.optJSONObject(0)
                            if (item != null) {
                                val data = item.optString("data", "")
                                val mime = item.optString("mime", "image/jpeg").ifBlank { "image/jpeg" }
                                if (data.isNotBlank()) {
                                    val payload = if (data.contains("base64,")) data.substringAfter("base64,") else data
                                    put("image", "data:$mime;base64,$payload")
                                }
                            }
                        }
                        else -> {
                            put("mode", "keyframes")
                            val extra = optJSONObject("extra_body") ?: JSONObject()
                            val images = org.json.JSONArray()
                            for (i in 0 until imgCount) {
                                val item = imgArr?.optJSONObject(i) ?: continue
                                val data = item.optString("data", "")
                                val mime = item.optString("mime", "image/jpeg").ifBlank { "image/jpeg" }
                                if (data.isNotBlank()) {
                                    val payload = if (data.contains("base64,")) data.substringAfter("base64,") else data
                                    images.put("data:$mime;base64,$payload")
                                }
                            }
                            extra.put("image", images)
                            extra.put("mode", "keyframes")
                            put("extra_body", extra)
                        }
                    }
                }
                remove("image_data")
            }
        }
        val path = if (config.baseUrl.contains("bigmodel.cn", ignoreCase = true)) "videos/generations" else "videos"
        val url = if (config.baseUrl.contains("bigmodel.cn", ignoreCase = true)) {
            "${config.normalizedBaseUrl()}/$path"
        } else {
            endpoint(config, path)
        }
        val response = client.newCall(Request.Builder().url(url)
            .authorize(config).header("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType())).build()).execute()
        val body = response.body?.string().orEmpty()
        android.util.Log.i("VideoApi", "视频API响应 code=${response.code} body前500=${body.take(500)}")
        return Response.Builder()
            .request(response.request)
            .protocol(response.protocol)
            .code(response.code)
            .message(response.message)
            .headers(response.headers)
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    fun executeImage(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): Response {
        if (CloudflareWorkersAiClient.matches(config)) {
            return CloudflareWorkersAiClient.generateImage(config, upstreamModel, rawRequest)
        }
        // 阶跃星辰图生图：/v1/images/edits + multipart（官方协议，generations+JSON 不支持参考图）
        if (rawRequest.contains("\"image_data\"") && stepEditMatches(config, upstreamModel)) {
            return executeImageEdit(config, upstreamModel, rawRequest)
        }
        // Agnes 官方协议(agnes-ai.com/docs)：文生图与图生图同一 generations 端点，
        // 参考图放 extra_body.image[]（支持 dataURI base64），response_format 必须在 extra_body 内。
        if (config.baseUrl.contains("agnes", ignoreCase = true)) {
            return agnesImage(config, upstreamModel, rawRequest)
        }
        if (HiApiImageClient.matches(config)) {
            val result = HiApiImageClient.generate(config, upstreamModel, rawRequest)
            val request = Request.Builder().url(endpoint(config, "images/generations")).build()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(result.httpCode)
                .message(if (result.httpCode in 200..299) "OK" else "HiAPI task failed")
                .body(result.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        // 猫猫中转(catcat) gemini image 型号：generations 端点不支持(500 do_request_failed)，
        // 必须走 chat/completions：文生图=纯文本消息；图生图=vision块+参考图。
        // 参考图必须压缩到 ≤512px，否则大 base64 会撞 Cloudflare 524（上游处理超100s被掐）。
        if (config.baseUrl.contains("catcat", ignoreCase = true) &&
            upstreamModel.contains("gemini", ignoreCase = true) &&
            upstreamModel.contains("image", ignoreCase = true)
        ) {
            return catcatGeminiImage(config, upstreamModel, rawRequest)
        }
        // 猫猫中转(catcat) 图生图：generations 端点会静默忽略参考图字段(表现为纯文生图)，
        // 带图时必须改走 JSON 版 /v1/images/edits；lite 无编辑能力，模型统一升级为 grok-imagine-image-2.0。
        if (config.baseUrl.contains("catcat", ignoreCase = true) &&
            rawRequest.contains("\"image_data\"")
        ) {
            return catcatGrokImageEdit(config, rawRequest)
        }
        // 商汤（token.sensenova.cn）：文生图走 generations；图生图走官方图片编辑接口
        // POST /v1/images/edits（JSON，同步），images 元素必须是 {"image_url": dataURL|公网URL}
        // 对象（裸字符串数组在 generations 会被上游随机忽略、在 edits 直接 400）；
        // watermark:false 去水印（实测唯一有效写法，watermark_enabled/water_mark 无效）；
        // edits 支持 size 直传（含 1344x3136 竖版）与 response_format:"b64_json"。
        if (config.baseUrl.contains("sensenova", ignoreCase = true)) {
            return senseNovaImage(config, upstreamModel, rawRequest)
        }
        val jsonBody = JSONObject(rawRequest).put("model", upstreamModel)
        // 智谱(bigmodel.cn) cogview：忠实还原旧版 APK 的适配(smali adaptToZhipuImage/zhipuSizeFor，
        // 源码化时丢失)——关水印 + 把 9:16 这类比例转成智谱要求的 WxH 尺寸，否则报 size 参数不合法。
        if (config.baseUrl.contains("bigmodel.cn", ignoreCase = true) &&
            upstreamModel.contains("cogview", ignoreCase = true)
        ) {
            adaptToZhipuImage(jsonBody)
        }
        // 通用兜底：size 若是比例串(9:16)而上游只认 WxH（如魔搭 ModelScope 报
        // "size format should be WxH"），发请求前先转成像素尺寸，避免第一跳就 400。
        val sizeVal = jsonBody.optString("size")
        if (sizeVal.contains(":")) {
            jsonBody.put("size", ratioToWxH(sizeVal))
        }
        // 魔搭(ModelScope)：图生成不支持同步调用，必须加 X-ModelScope-Async-Mode: true
        // 提交拿 task_id → 轮询 GET /tasks/{task_id} 直到 SUCCEEDED，把结果拼回 OpenAI 响应。
        if (config.baseUrl.contains("modelscope", ignoreCase = true)) {
            return modelscopeImage(config, upstreamModel, jsonBody)
        }
        fun execute(body: String): Response = client.newCall(
            Request.Builder().url(endpoint(config, "images/generations"))
                .authorize(config).header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType())).build()
        ).execute()

        var response = execute(jsonBody.toString())
        repeat(2) {
            if (response.isSuccessful) return response
            val errorPreview = response.peekBody(8_192).string()
            var changed = false

            // Compatible vendors do not agree on size values. If the upstream
            // advertises accepted pixels, preserve the requested aspect ratio.
            val requestedSize = jsonBody.optString("size")
            if (':' in requestedSize) {
                val supported = PIXEL_SIZE.findAll(errorPreview).map { it.value }.distinct().toList()
                closestPixelSize(requestedSize, supported)?.let { retrySize ->
                    jsonBody.put("size", retrySize)
                    changed = true
                }
            }

            // Some image relays cap prompts (commonly 512 CJK chars/words). Retry
            // conservatively by Unicode code point so Chinese and emoji stay valid.
            if (errorPreview.contains("prompt_too_long", ignoreCase = true) ||
                errorPreview.contains("prompt is too long", ignoreCase = true)
            ) {
                val max = PROMPT_RANGE.find(errorPreview)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val prompt = jsonBody.optString("prompt")
                if (max != null && max > 0) {
                    val shortened = truncateCodePoints(prompt, max)
                    if (shortened != prompt) {
                        jsonBody.put("prompt", shortened)
                        changed = true
                    }
                }
            }

            if (!changed) return response
            response.close()
            response = execute(jsonBody.toString())
        }
        return response
    }

    /**
     * 猫猫中转 grok 图生图：JSON 版 POST /v1/images/edits
     * body: {model:"grok-imagine-image-2.0", prompt, image:{url:"data:mime;base64,..."}, n:1}
     * 响应 b64_json → 落 GeneratedImageStore → 对客户端返回标准 {"url": ...} 本地能力 url。
     */
    private fun catcatGrokImageEdit(config: ApiProviderConfig, rawRequest: String): Response {
        val requestId = Request.Builder().url(endpoint(config, "images/edits")).build()
        fun relay(code: Int, message: String, payload: String): Response = Response.Builder()
            .request(requestId).protocol(Protocol.HTTP_1_1)
            .code(code).message(message)
            .body(payload.toResponseBody("application/json".toMediaType()))
            .build()
        val o = runCatching { JSONObject(rawRequest) }.getOrElse { JSONObject() }
        val prompt = o.optString("prompt", "")
        val first = o.optJSONArray("image_data")?.optJSONObject(0)
        var dataUrl = first?.optString("data", "").orEmpty()
        if (dataUrl.isBlank()) {
            return relay(400, "Bad Request", JSONObject().put("error", JSONObject()
                .put("message", "猫猫图生图需要参考图 image_data")
                .put("type", "invalid_request_error")).toString())
        }
        val mime = first?.optString("mime", "image/jpeg")?.ifBlank { "image/jpeg" } ?: "image/jpeg"
        if (!dataUrl.startsWith("data:")) dataUrl = "data:$mime;base64,$dataUrl"
        val req = JSONObject()
            .put("model", "grok-imagine-image-2.0")
            .put("prompt", prompt)
            .put("image", JSONObject().put("url", dataUrl))
            .put("n", 1)
            .put("response_format", "b64_json")
        val response = client.newCall(
            Request.Builder().url(requestId.url)
                .authorize(config).header("Content-Type", "application/json")
                .post(req.toString().toRequestBody("application/json".toMediaType())).build()
        ).execute()
        val code = response.code
        val message = response.message
        val body = response.body?.string().orEmpty()
        android.util.Log.i("ImageRoute", "猫猫图生图: HTTP=$code body前120=${body.take(120)}")
        if (!response.isSuccessful) return relay(code, message, body)
        val out = runCatching { JSONObject(body) }.getOrElse { return relay(502, "relay returned non-JSON body", body) }
        val data = out.optJSONArray("data") ?: return relay(code, message, body)
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val b64 = item.optString("b64_json", "").removePrefix("data:").substringAfter("base64,")
            if (b64.isBlank()) continue
            val bytes = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val localUrl = GeneratedImageStore.saveBytes(bytes, sniffImageMime(bytes))
            item.remove("b64_json")
            item.put("url", localUrl)
        }
        return relay(code, message, out.toString())
    }

    /**
     * 猫猫中转 gemini image 型号专用（chat/completions 出图）：
     * 文生图=纯文本消息；图生图=vision 块(text + image_url dataURL)。
     * 参考图先解码缩到 ≤512px 再回填 dataURL，避免 Cloudflare 524。
     * 响应 content 内嵌 data:image/...;base64 → 落 GeneratedImageStore → 返回标准 {"url": ...}。
     */
    private fun catcatGeminiImage(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): Response {
        // 文生图 → chat/completions；图生图 → multipart images/edits（两个端点不同）
        val requestId = Request.Builder().url(endpoint(config, "chat/completions")).build()
        fun relay(code: Int, message: String, payload: String): Response = Response.Builder()
            .request(requestId).protocol(Protocol.HTTP_1_1)
            .code(code).message(message)
            .body(payload.toResponseBody("application/json".toMediaType()))
            .build()
        val o = runCatching { JSONObject(rawRequest) }.getOrElse { JSONObject() }
        val prompt = o.optString("prompt", "")
        val refs = o.optJSONArray("image_data")
        val firstRef = refs?.optJSONObject(0)
        if (refs != null && refs.length() > 0 && firstRef != null &&
            !firstRef.optString("data", "").isBlank()) {
            return catcatGeminiEditMultipart(config, upstreamModel, prompt, refs)
        }
        // 纯文生图: chat/completions（猫猫 gemini image 型号文生图走 chat 最稳）
        val messages = org.json.JSONArray()
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val req = JSONObject()
            .put("model", upstreamModel)
            .put("messages", messages)
            .put("stream", false)
        val response = client.newCall(
            Request.Builder().url(requestId.url)
                .authorize(config).header("Content-Type", "application/json")
                .post(req.toString().toRequestBody("application/json".toMediaType())).build()
        ).execute()
        val code = response.code
        val message = response.message
        val body = response.body?.string().orEmpty()
        android.util.Log.i("ImageRoute", "猫猫gemini出图: HTTP=$code body前120=${body.take(120)}")
        if (!response.isSuccessful) return relay(code, message, body)
        val out = runCatching { JSONObject(body) }.getOrElse { return relay(502, "relay returned non-JSON body", body) }
        val contentText = out.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "").orEmpty()
        val m = Regex("data:image/(?:png|jpeg|webp);base64,([A-Za-z0-9+/=]+)").find(contentText)
            ?: return relay(502, "no inline image in chat response", body.take(2000))
        val bytes = runCatching { android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return relay(502, "inline image decode failed", body.take(2000))
        val localUrl = GeneratedImageStore.saveBytes(bytes, sniffImageMime(bytes))
        val result = JSONObject()
            .put("created", (System.currentTimeMillis() / 1000).toInt())
            .put("data", org.json.JSONArray().put(
                JSONObject().put("url", localUrl).put("revised_prompt", "")))
        android.util.Log.i("ImageRoute", "猫猫gemini出图: 成功落盘 ${bytes.size}B → $localUrl")
        return relay(200, "OK", result.toString())
    }

    /** 解码 bitmap 缩放至最大边 maxPx 后重编码 JPEG（失败时返回原字节） */
    private fun downscaleBitmap(bytes: ByteArray, mime: String, maxPx: Int): ByteArray {
        return try {
            val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val scale = minOf(1f, maxPx.toFloat() / maxOf(src.width, src.height))
            if (scale >= 1f) { src.recycle(); return bytes }
            val dst = android.graphics.Bitmap.createScaledBitmap(src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1), true)
            val bos = java.io.ByteArrayOutputStream()
            dst.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, bos)
            dst.recycle(); src.recycle()
            bos.toByteArray()
        } catch (t: Throwable) { bytes }
    }

    /** 猫猫 gemini 图生图: 标准 OpenAI multipart POST /v1/images/edits（参考图压≤512px防上游502） */
    private fun catcatGeminiEditMultipart(config: ApiProviderConfig, upstreamModel: String, prompt: String, refs: org.json.JSONArray): Response {
        val requestId = Request.Builder().url(endpoint(config, "images/edits")).build()
        fun relay(code: Int, message: String, payload: String): Response = Response.Builder()
            .request(requestId).protocol(Protocol.HTTP_1_1)
            .code(code).message(message)
            .body(payload.toResponseBody("application/json".toMediaType()))
            .build()
        val partBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", upstreamModel)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("response_format", "b64_json")
        for (i in 0 until refs.length()) {
            val ref = refs.optJSONObject(i) ?: continue
            val dataUrl = ref.optString("data", "")
            if (dataUrl.isBlank()) continue
            val mime = ref.optString("mime", "image/jpeg").ifBlank { "image/jpeg" }
            // 兼容裸 base64（App UI 上传无 dataURI 前缀）+ 解码后校验非空
            val b64Payload = if (dataUrl.contains("base64,")) dataUrl.substringAfter("base64,") else dataUrl
            var bytes = runCatching { android.util.Base64.decode(b64Payload.trim(), android.util.Base64.DEFAULT) }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            bytes = downscaleBitmap(bytes, mime, 512)
            val mediaType = if (mime == "image/png") "image/png".toMediaType() else "image/jpeg".toMediaType()
            partBuilder.addFormDataPart("image", "ref_$i.jpg", bytes.toRequestBody(mediaType))
        }
        val response = client.newCall(
            Request.Builder().url(requestId.url)
                .authorize(config)
                .post(partBuilder.build()).build()
        ).execute()
        val code = response.code
        val message = response.message
        val body = response.body?.string().orEmpty()
        android.util.Log.i("ImageRoute", "猫猫gemini图生图(edits multipart): HTTP=$code body前120=${body.take(120)}")
        if (!response.isSuccessful) return relay(code, message, body)
        val out = runCatching { JSONObject(body) }.getOrElse { return relay(502, "relay returned non-JSON body", body) }
        val b64 = out.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json", "").orEmpty()
        if (b64.isBlank()) return relay(502, "no image in edits response", body.take(2000))
        val bytes = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return relay(502, "b64 decode failed", body.take(2000))
        val localUrl = GeneratedImageStore.saveBytes(bytes, sniffImageMime(bytes))
        val result = JSONObject()
            .put("created", (System.currentTimeMillis() / 1000).toInt())
            .put("data", org.json.JSONArray().put(
                JSONObject().put("url", localUrl).put("revised_prompt", "")))
        android.util.Log.i("ImageRoute", "猫猫gemini图生图: 成功落盘 ${bytes.size}B → $localUrl")
        return relay(200, "OK", result.toString())
    }

    /**
     * Agnes 图像模型官方协议（agnes-ai.com/zh-Hans/docs/agnes-image-21-flash）：
     * 端点 POST /v1/images/generations；文生图必填 model/prompt/size（档位 1K~4K，配合 ratio）；
     * 图生图/多图合成：extra_body.image[] = [url 或 dataURI base64]；response_format 必须在
     * extra_body 内（顶层 response_format 会报错），图生图推荐 b64_json。响应 data[0].url 或 b64_json。
     * App 的 size 是 9:16 这类比例 → 转 ratio + 默认档位 1K；已有 WxH 尺寸则直接透传。
     */
    private fun agnesImage(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): Response {
        val requestId = Request.Builder().url(endpoint(config, "images/generations")).build()
        fun relay(code: Int, message: String, payload: String): Response = Response.Builder()
            .request(requestId).protocol(Protocol.HTTP_1_1)
            .code(code).message(message)
            .body(payload.toResponseBody("application/json".toMediaType()))
            .build()
        val reqIn = runCatching { JSONObject(rawRequest) }.getOrElse { return relay(400, "Bad Request", "{\"error\":{\"message\":\"invalid JSON body\",\"type\":\"invalid_request_error\"}}") }
        val prompt = reqIn.optString("prompt", "")
        if (prompt.isBlank()) {
            return relay(400, "Bad Request", "{\"error\":{\"message\":\"prompt is required\",\"type\":\"invalid_request_error\"}}")
        }
        val outReq = JSONObject().put("model", upstreamModel).put("prompt", prompt)
        // size: App 发比例(9:16) → 按模型版本转：2.1+=档位1K+ratio；2.0 无 ratio 参数，
        // 只认精确像素（官方示例 1024x768/1024x1024/768x1024），发档位串会被拒。
        val sizeRaw = reqIn.optString("size", "1K")
        val isTierSize = upstreamModel.contains("2.1")
        val extraBody = JSONObject()
        if (sizeRaw.contains(':')) {
            if (isTierSize) {
                outReq.put("size", "1K")
                outReq.put("ratio", agnesRatioFor(sizeRaw))
            } else {
                outReq.put("size", agnesPixelSizeFor(sizeRaw))
            }
        } else {
            outReq.put("size", sizeRaw.ifBlank { "1024x1024" })
        }
        // 参考图 → extra_body.image[] (dataURI)；有图时强制 b64_json 防坏 URL，无图默认也用 b64_json 落本地
        val refs = reqIn.optJSONArray("image_data")
        if (refs != null && refs.length() > 0) {
            val images = org.json.JSONArray()
            for (i in 0 until refs.length()) {
                val ref = refs.optJSONObject(i) ?: continue
                var dataUrl = ref.optString("data", "")
                if (dataUrl.isBlank()) continue
                val mime = ref.optString("mime", "image/jpeg").ifBlank { "image/jpeg" }
                // App UI 上传的 data 可能是裸 base64（无 dataURI 前缀），兼容两种格式；
                // 解码后必须校验非空——Base64.decode("") 不报错而是返回空数组，会把空图发给上游。
                val b64Payload = if (dataUrl.contains("base64,")) dataUrl.substringAfter("base64,") else dataUrl
                var bytes = runCatching { android.util.Base64.decode(b64Payload.trim(), android.util.Base64.DEFAULT) }
                    .getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                bytes = downscaleBitmap(bytes, mime, 1024)
                images.put("data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }
            if (images.length() > 0) {
                extraBody.put("image", images)
                extraBody.put("response_format", "b64_json")
            }
        }
        if (!extraBody.has("response_format")) {
            // 文生图也走 extra_body.response_format（与图生图已验证成功路径同构）。
            // 不要发顶层 return_base64:true——实测 Agnes 2.0 收到该字段 360s 不回包（疑似上游缺陷）；
            // 旧版 1.0.7 文生图发 url 格式从未超时。url 响应由下方下载落盘兜底。
            extraBody.put("response_format", "url")
        }
        if (extraBody.length() > 0) outReq.put("extra_body", extraBody)
        android.util.Log.i("ImageRoute", "Agnes生图: size=$sizeRaw 带参考图=${refs != null && refs.length() > 0} 模型=$upstreamModel")
        val response = longClient.newCall(
            Request.Builder().url(requestId.url)
                .authorize(config).header("Content-Type", "application/json")
                .post(outReq.toString().toRequestBody("application/json".toMediaType())).build()
        ).execute()
        val code = response.code
        val message = response.message
        val body = response.body?.string().orEmpty()
        android.util.Log.i("ImageRoute", "Agnes生图: HTTP=$code body前120=${body.take(120)}")
        if (!response.isSuccessful) return relay(code, message, body)
        val respJson = runCatching { JSONObject(body) }.getOrElse { return relay(502, "relay returned non-JSON body", body) }
        val item = respJson.optJSONArray("data")?.optJSONObject(0)
            ?: return relay(code, message, body)
        val localUrl: String?
        val b64 = item.optString("b64_json", "")
        val remoteUrl = item.optString("url", "")
        localUrl = when {
            !b64.isBlank() -> runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { GeneratedImageStore.saveBytes(it, sniffImageMime(it)) }
            !remoteUrl.isBlank() -> {
                val remote = remoteUrl
                val downloaded: String? = if (remote.startsWith("http") && !remote.contains("127.0.0.1") && !remote.contains("localhost")) {
                    runCatching {
                        longClient.newCall(Request.Builder().url(remote).build()).execute().use { img ->
                            if (img.isSuccessful) {
                                val bytes = img.body?.bytes()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    return@use GeneratedImageStore.saveBytes(bytes, sniffImageMime(bytes))
                                }
                            }
                            null
                        }
                    }.getOrNull()
                } else null
                downloaded ?: remote
            }
            else -> null
        }
        if (localUrl == null) return relay(code, message, body)
        val fixed = JSONObject().put("created", respJson.optInt("created", (System.currentTimeMillis() / 1000).toInt()))
            .put("data", org.json.JSONArray().put(
                JSONObject().put("url", localUrl).put("revised_prompt", item.optString("revised_prompt", ""))))
        android.util.Log.i("ImageRoute", "Agnes生图: 成功落盘 → $localUrl")
        return relay(200, "OK", fixed.toString())
    }

    /** Agnes 支持的 ratio 白名单，不在表内则回落 1:1 */
    private fun agnesRatioFor(sizeRaw: String): String {
        val supported = setOf("1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9")
        val s = sizeRaw.trim()
        return if (s in supported) s else "1:1"
    }

    /** Agnes Image 2.0 只认精确像素尺寸；比例→方向就近像素（// ponytail: 2.0 无 ratio，9:16 只能近似 3:4，要精确竖屏升 2.1） */
    private fun agnesPixelSizeFor(ratio: String): String = when (ratio.trim()) {
        "16:9", "3:2", "21:9" -> "1024x768"
        "9:16", "2:3", "3:4" -> "768x1024"
        else -> "1024x1024"
    }

    private fun sniffImageMime(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() -> "image/png"
        bytes.size > 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
        bytes.size > 4 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
        else -> "image/jpeg"
    }

    /** 还原旧版 APK：智谱 cogview 生图适配——关水印 + 比例尺寸转 WxH */
    private fun adaptToZhipuImage(body: JSONObject) {
        body.put("watermark_enabled", false)
        val size = body.optString("size", "")
        if (!size.contains(":") && size.isNotEmpty()) return
        body.put("size", zhipuSizeFor(size))
    }

    /** 还原旧版 APK zhipuSizeFor 映射表(逐条对齐 smali) */
    private fun zhipuSizeFor(ratio: String): String = when (ratio) {
        "9:16" -> "720x1440"
        "16:9" -> "1440x720"
        "3:4" -> "864x1152"
        "4:3" -> "1152x864"
        "2:3" -> "864x1152"
        "3:2" -> "1152x864"
        "21:9" -> "1440x720"
        else -> "1024x1024"
    }

    /**
     * 通用比例→WxH 转换（OpenAI 兼容端点普遍只认像素尺寸，如魔搭 ModelScope 报
     * "size format should be WxH"）。保持用户选的比例，短边 768 / 长边按比例，且为 16 的倍数。
     */
    internal fun ratioToWxH(ratio: String): String {
        val parts = ratio.split(':')
        if (parts.size != 2) return "1024x1024"
        val w = parts[0].toDoubleOrNull() ?: return "1024x1024"
        val h = parts[1].toDoubleOrNull() ?: return "1024x1024"
        if (w <= 0 || h <= 0) return "1024x1024"
        fun snap(v: Double): Int = (v / 16.0).toInt().coerceAtLeast(48) * 16
        return if (w >= h) {
            val long = 1024.0; val short = long * h / w
            "${snap(long)}x${snap(short)}"
        } else {
            val short = 768.0; val long = short * w / h
            "${snap(short)}x${snap(long)}"
        }
    }

    internal fun closestPixelSize(ratio: String, supported: List<String>): String? {
        val target = ratio.split(':').takeIf { it.size == 2 }
            ?.let { parts -> parts[0].toDoubleOrNull()?.div(parts[1].toDoubleOrNull() ?: return null) }
            ?: return null
        return supported.mapNotNull { size ->
            val match = PIXEL_SIZE.matchEntire(size) ?: return@mapNotNull null
            val width = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val height = match.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            size to kotlin.math.abs(width / height - target)
        }.minByOrNull { it.second }?.first
    }

    internal fun truncateCodePoints(text: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0) return ""
        val count = text.codePointCount(0, text.length)
        if (count <= maxCodePoints) return text
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints)).trimEnd()
    }

    private val PIXEL_SIZE = Regex("(\\d{2,5})x(\\d{2,5})", RegexOption.IGNORE_CASE)
    private val PROMPT_RANGE = Regex("between\\s*\\[\\s*\\d+\\s*,\\s*(\\d+)\\s*]", RegexOption.IGNORE_CASE)

    /** 阶跃 edits 协议命中条件：baseUrl 是 stepfun 或模型名是 step-image-edit* */
    private fun stepEditMatches(config: ApiProviderConfig, upstreamModel: String): Boolean =
        config.baseUrl.contains("stepfun", ignoreCase = true) ||
            upstreamModel.startsWith("step-image-edit", ignoreCase = true)

    /**
     * 阶跃星辰图片编辑（图生图）：
     * POST {base}/images/edits  multipart/form-data:
     *   model=step-image-edit-2, image=<图片文件或base64>, prompt, response_format=b64_json
     * 响应 data[0].b64_json → 转成 data URL 保持与中转 tab 的图片展示兼容。
     */
    private fun executeImageEdit(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): Response {
        val o = JSONObject(rawRequest)
        val prompt = o.optString("prompt", "")
        val refs = o.optJSONArray("image_data")
        val first = refs?.optJSONObject(0)
        val dataUrl = first?.optString("data", "").orEmpty()
        val mime = first?.optString("mime", "image/png")?.ifBlank { "image/png" } ?: "image/png"
        if (dataUrl.isBlank()) {
            val err = JSONObject().put("error", JSONObject().put(
                "message", "step-image-edit 需要参考图 image_data").put("type", "invalid_request_error"))
            return Response.Builder().request(Request.Builder().url(endpoint(config, "images/edits")).build())
                .protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                .body(err.toString().toResponseBody("application/json".toMediaType())).build()
        }
        val base64 = dataUrl.substringAfter("base64,", dataUrl)
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val ext = when { mime.contains("jpeg") || mime.contains("jpg") -> "jpg"; mime.contains("webp") -> "webp"; else -> "png" }

        val boundary = "----MWBoundary" + java.util.UUID.randomUUID().toString().replace("-", "")
        fun part(name: String, value: String) =
            ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray(Charsets.UTF_8)
        val head = ByteArrayOutputStream().apply {
            write(part("model", upstreamModel))
            write(part("prompt", prompt.take(512)))
            write(part("response_format", "b64_json"))
            o.optInt("seed", -1).takeIf { it >= 0 }?.let { write(part("seed", it.toString())) }
        }.toByteArray()
        val fileHead = ("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"ref.$ext\"\r\n" +
            "Content-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = head + fileHead + bytes + byteArrayOf(13, 10) + tail

        val request = Request.Builder().url(endpoint(config, "images/edits"))
            .authorize(config)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .post(body.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
            .build()
        val resp = client.newCall(request).execute()
        // 响应处理：统一把有效图片写进 url 字段（data URL 或远程 URL），
        // 因为前端只解析 data[].url。阶跃在 response_format=b64_json 时只返回 b64_json 字段，
        // 之前只改 b64_json 字段导致前端"生图未返回图片"。
        if (resp.isSuccessful) {
            val text = resp.body?.string().orEmpty()
            val out = runCatching {
                val root = JSONObject(text)
                val data = root.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    val item = data.optJSONObject(0)
                    val b64 = item?.optString("b64_json", "").orEmpty()
                    val remoteUrl = item?.optString("url", "").orEmpty()
                    val displayUrl = when {
                        b64.isNotBlank() -> if (b64.startsWith("data:")) b64 else "data:$mime;base64,$b64"
                        remoteUrl.isNotBlank() -> remoteUrl
                        else -> ""
                    }
                    if (displayUrl.isNotBlank()) {
                        item.put("url", displayUrl)
                    }
                }
                root.toString()
            }.getOrElse { text }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(resp.code).message(resp.message)
                .body(out.toResponseBody("application/json".toMediaType())).build()
        }
        return resp
    }

    /**
     * 魔搭(ModelScope) 图生成异步适配：
     * 同步调用报 "image-gen task currently does not support synchronous calls"，必须
     * 请求头 X-ModelScope-Async-Mode: true → 响应 {task_id}；
     * 轮询 GET /v1/tasks/{task_id}（须带 X-ModelScope-Task-Type: image_generation）
     * 直到 task_status=SUCCEED/SUCCEEDED/FAILED；
     * 完成后图片输出在 output_images[]（或兼容 data[].url），拼回 OpenAI 风格 data[].url。
     * 图生图参考图：编辑模型用顶层 image_url 字段透传 base64 dataURI。
     */
    private fun modelscopeImage(config: ApiProviderConfig, upstreamModel: String, jsonBody: JSONObject): Response {
        val requestId = Request.Builder().url(endpoint(config, "images/generations")).build()
        fun relay(code: Int, message: String, payload: String): Response = Response.Builder()
            .request(requestId).protocol(Protocol.HTTP_1_1)
            .code(code).message(message)
            .body(payload.toResponseBody("application/json".toMediaType()))
            .build()
        // 参考图（图生图）：把 image_data 转 dataURI 放 image_url 字段
        // 官方例子（modelscope 模型页 API 示例）用顶层 image_url；Qwen/Qwen-Image-Edit 等编辑模型必需
        jsonBody.optJSONArray("image_data")?.let { arr ->
            if (arr.length() > 0) {
                val item = arr.optJSONObject(0)
                val data = item?.optString("data", "").orEmpty()
                val mime = item?.optString("mime", "image/png")?.ifBlank { "image/png" } ?: "image/png"
                if (data.isNotBlank()) {
                    val payload = if (data.contains("base64,")) data.substringAfter("base64,") else data
                    jsonBody.put("image_url", "data:$mime;base64,$payload")
                }
            }
            jsonBody.remove("image_data")
        }
        // 若无参考图则为文生图，无需 image_url
        val submitResp = runCatching {
            client.newCall(Request.Builder().url(requestId.url)
                .authorize(config)
                .header("Content-Type", "application/json")
                .header("X-ModelScope-Async-Mode", "true")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType())).build()
            ).execute()
        }.getOrElse { return relay(502, "Bad Gateway", "{\"error\":{\"message\":\"${it.message ?: it.javaClass.simpleName}\"}}") }
        val submitBody = submitResp.body?.string().orEmpty()
        android.util.Log.i("MSImage", "提交 HTTP=${submitResp.code} body前300=${submitBody.take(300)}")
        if (!submitResp.isSuccessful) return relay(submitResp.code, submitResp.message, submitBody.ifBlank { "{\"error\":{\"message\":\"submit failed\"}}" })
        val taskId = runCatching { JSONObject(submitBody).optString("task_id", "") }.getOrNull().orEmpty()
        if (taskId.isBlank()) {
            // 万一上游直接同步返回了结果（未来行为变化），原样透传
            return relay(200, "OK", submitBody)
        }
        // 轮询，最长 5 分钟
        val deadline = System.currentTimeMillis() + 300_000
        var lastBody = submitBody
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000)
            val pollResp = runCatching {
                client.newCall(Request.Builder().url(endpoint(config, "tasks/$taskId"))
                    .authorize(config)
                    .header("X-ModelScope-Task-Type", "image_generation")
                    .get().build()).execute()
            }.getOrElse { return relay(502, "Bad Gateway", "{\"error\":{\"message\":\"poll failed: ${it.message}\"}}") }
            lastBody = pollResp.body?.string().orEmpty()
            pollResp.close()
            val pj = runCatching { JSONObject(lastBody) }.getOrNull()
            val status = pj?.optString("task_status", "") ?: ""
            android.util.Log.i("MSImage", "轮询 $taskId → $status")
            when (status) {
                "SUCCEED", "SUCCEEDED" -> {
                    // 拼回 OpenAI 风格：优先 output_images[]，其次 data[].url / output.url
                    val urls = mutableListOf<String>()
                    pj?.optJSONArray("output_images")?.let { a ->
                        for (i in 0 until a.length()) a.optString(i).takeIf(String::isNotBlank)?.let(urls::add)
                    }
                    if (urls.isEmpty()) pj?.optJSONArray("data")?.let { a ->
                        for (i in 0 until a.length()) a.optJSONObject(i)?.optString("url","")?.takeIf(String::isNotBlank)?.let(urls::add)
                    }
                    if (urls.isEmpty()) pj?.optJSONObject("output")?.optString("url","")?.takeIf(String::isNotBlank)?.let(urls::add)
                    val out = JSONObject().put("created", (System.currentTimeMillis()/1000).toInt())
                        .put("data", org.json.JSONArray().apply { urls.forEach { put(JSONObject().put("url", it)) } })
                    return relay(200, "OK", out.toString())
                }
                "FAILED", "CANCELED" -> {
                    val msg = pj?.optJSONObject("errors")?.toString()
                        ?: pj?.optString("message", "")?.takeIf(String::isNotBlank)
                        ?: "task $status"
                    return relay(502, "Bad Gateway", "{\"error\":{\"message\":\"魔搭任务失败: $msg\"}}")
                }
            }
        }
        return relay(504, "Gateway Timeout", "{\"error\":{\"message\":\"魔搭生图任务超时\"}}")
    }
}