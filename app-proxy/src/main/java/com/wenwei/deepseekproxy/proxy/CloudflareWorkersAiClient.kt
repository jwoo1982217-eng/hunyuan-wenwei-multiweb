package com.wenwei.deepseekproxy.proxy

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Official Cloudflare Workers AI REST/OpenAI-compatible adapter. */
object CloudflareWorkersAiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val accountPath = Regex(
        "https?://api\\.cloudflare\\.com/client/v4/accounts/([^/]+)/ai(?:/v1)?(?:/.*)?$",
        RegexOption.IGNORE_CASE
    )

    fun matches(config: ApiProviderConfig): Boolean = accountId(config) != null

    internal fun accountId(config: ApiProviderConfig): String? =
        accountPath.matchEntire(config.normalizedBaseUrl())?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)

    internal fun apiBase(config: ApiProviderConfig): String? = accountId(config)?.let {
        "https://api.cloudflare.com/client/v4/accounts/$it/ai"
    }

    suspend fun test(config: ApiProviderConfig): ApiTestResult {
        val startedAt = System.nanoTime()
        val base = apiBase(config) ?: return ApiTestResult(false, "Cloudflare Account ID 地址格式无效")
        return runCatching {
            val request = Request.Builder()
                .url("$base/models/search?per_page=1000&hide_experimental=true")
                .authorize(config)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val latency = (System.nanoTime() - startedAt) / 1_000_000
                if (!response.isSuccessful) {
                    return@use ApiTestResult(false, "HTTP ${response.code}: ${body.take(300)}", latencyMs = latency)
                }
                val result = parseModelSearch(body)
                result.copy(message = "${result.message}，${latency}ms", latencyMs = latency)
            }
        }.getOrElse {
            ApiTestResult(
                false,
                "Cloudflare 网络异常：${it.message ?: it.javaClass.simpleName}",
                latencyMs = (System.nanoTime() - startedAt) / 1_000_000
            )
        }
    }

    internal fun isPaidPlanRestriction(code: Int, body: String): Boolean {
        if (code != 403) return false
        val text = body.lowercase()
        return "workers paid plan" in text ||
            "not available on the workers free plan" in text ||
            ("requires" in text && "paid plan" in text)
    }

    internal fun parseModelSearch(body: String): ApiTestResult {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse {
            return ApiTestResult(false, "Cloudflare 模型列表格式无法解析")
        }
        if (root["success"]?.jsonPrimitive?.contentOrNull.equals("false", ignoreCase = true)) {
            return ApiTestResult(false, "Cloudflare 返回失败：${root["errors"] ?: body.take(240)}")
        }
        val models = root["result"] as? JsonArray ?: JsonArray(emptyList())
        val chat = linkedSetOf<String>()
        val images = linkedSetOf<String>()
        models.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val id = modelId(item) ?: return@forEach
            val task = taskText(item).lowercase()
            when {
                isImageTask(task) && !isImageEditingModel(id) -> images += id
                isChatTask(task) -> chat += id
            }
        }
        if (chat.isEmpty() && images.isEmpty()) {
            return ApiTestResult(false, "Cloudflare 连接成功，但没有解析到可用的文本或生图模型")
        }
        return ApiTestResult(
            true,
            "Cloudflare 连接成功，发现 ${chat.size + images.size} 个模型（聊天 ${chat.size}，生图 ${images.size}）",
            chatModels = chat.toList(),
            imageModels = images.toList()
        )
    }

    private fun modelId(item: JsonObject): String? = sequenceOf("name", "id", "model")
        .mapNotNull { key -> item[key]?.jsonPrimitive?.contentOrNull?.trim() }
        .firstOrNull { it.startsWith("@cf/") }

    private fun taskText(item: JsonObject): String {
        val task = item["task"]
        return when (task) {
            is JsonObject -> sequenceOf("name", "id", "description")
                .mapNotNull { task[it]?.jsonPrimitive?.contentOrNull }.joinToString(" ")
            else -> task?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    private fun isImageTask(task: String): Boolean = listOf(
        "text-to-image", "text to image", "image generation", "image-generation"
    ).any(task::contains)

    private fun isChatTask(task: String): Boolean = listOf(
        "text generation", "text-generation", "chat", "conversational"
    ).any(task::contains)

    private fun isImageEditingModel(id: String): Boolean = listOf(
        "inpaint", "img2img", "image-to-image", "image_to_image"
    ).any { id.contains(it, ignoreCase = true) }

    fun generateImage(config: ApiProviderConfig, model: String, rawRequest: String): Response {
        val base = apiBase(config) ?: return errorResponse(400, "invalid_cloudflare_url", "Cloudflare Account ID 地址格式无效")
        val incoming = runCatching { JSONObject(rawRequest) }.getOrDefault(JSONObject())
        val prompt = incoming.optString("prompt").trim()
        if (prompt.isBlank()) return errorResponse(400, "missing_prompt", "生图提示词为空")

        val payload = JSONObject().put("prompt", prompt)
        parsePixelSize(incoming.optString("size"))?.let { (width, height) ->
            payload.put("width", width).put("height", height)
        }
        val endpoint = "$base/run/${model.trim()}"
        var response = if (usesMultipartInput(model)) {
            executeMultipartRun(config, endpoint, payload)
        } else {
            executeRun(config, endpoint, payload)
        }
        if (!response.isSuccessful && (payload.has("width") || payload.has("height"))) {
            val preview = response.peekBody(8_192).string()
            if (preview.contains("width", true) || preview.contains("height", true) || preview.contains("dimension", true)) {
                response.close()
                payload.remove("width")
                payload.remove("height")
                response = if (usesMultipartInput(model)) {
                    executeMultipartRun(config, endpoint, payload)
                } else {
                    executeRun(config, endpoint, payload)
                }
            }
        }
        if (!response.isSuccessful) return normalizeCloudflareError(response)

        response.use { upstream ->
            val bytes = upstream.body?.bytes() ?: ByteArray(0)
            val contentType = upstream.header("Content-Type").orEmpty().lowercase()
            val openAiBody = when {
                contentType.startsWith("image/") -> openAiImageJson(Base64.encodeToString(bytes, Base64.NO_WRAP))
                else -> normalizeJsonImage(bytes.toString(Charsets.UTF_8))
                    ?: return errorResponse(502, "cloudflare_image_format", "Cloudflare 生图成功，但图片返回格式无法识别")
            }
            return Response.Builder()
                .request(upstream.request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json")
                .body(openAiBody.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun executeRun(config: ApiProviderConfig, endpoint: String, payload: JSONObject): Response =
        client.newCall(
            Request.Builder().url(endpoint).authorize(config)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()

    private fun executeMultipartRun(
        config: ApiProviderConfig,
        endpoint: String,
        payload: JSONObject
    ): Response {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            payload.keys().forEach { key -> addFormDataPart(key, payload.opt(key)?.toString().orEmpty()) }
        }.build()
        return client.newCall(
            Request.Builder().url(endpoint).authorize(config).post(multipart).build()
        ).execute()
    }

    internal fun usesMultipartInput(model: String): Boolean = listOf(
        "flux-2-dev", "flux-2-klein-4b", "flux-2-klein-9b"
    ).any { model.contains(it, ignoreCase = true) }

    private fun normalizeCloudflareError(response: Response): Response {
        val body = response.peekBody(16_384).string()
        if (response.code == 429 && body.contains("daily free allocation", ignoreCase = true)) {
            response.close()
            return errorResponse(
                429,
                "cloudflare_daily_quota_exhausted",
                "Cloudflare 今日 10,000 Neurons 免费额度已用完，将在北京时间明天上午 8:00 恢复"
            )
        }
        return response
    }

    internal fun normalizeJsonImage(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val directData = root.optJSONArray("data")
        if (directData != null && directData.length() > 0) {
            val first = directData.optJSONObject(0)
            val b64 = first?.optString("b64_json").orEmpty()
            val url = first?.optString("url").orEmpty()
            if (b64.isNotBlank() || url.isNotBlank()) return root.toString()
        }
        val result = root.opt("result")
        val image = when (result) {
            is JSONObject -> sequenceOf("image", "b64_json", "base64")
                .map { result.optString(it) }.firstOrNull(String::isNotBlank)
            is String -> result.takeIf(String::isNotBlank)
            else -> null
        } ?: return null
        return openAiImageJson(image.removePrefix("data:image/png;base64,").removePrefix("data:image/jpeg;base64,"))
    }

    private fun openAiImageJson(base64: String): String = JSONObject().apply {
        put("created", System.currentTimeMillis() / 1000)
        put("data", JSONArray().put(JSONObject().put("b64_json", base64)))
    }.toString()

    private fun parsePixelSize(size: String): Pair<Int, Int>? {
        val match = Regex("(\\d{2,5})x(\\d{2,5})", RegexOption.IGNORE_CASE).matchEntire(size.trim()) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return width to height
    }

    private fun Request.Builder.authorize(config: ApiProviderConfig): Request.Builder = apply {
        if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
    }

    private fun errorResponse(code: Int, type: String, message: String): Response {
        val request = Request.Builder().url("https://api.cloudflare.com/").build()
        val body = JSONObject().put("error", JSONObject().put("type", type).put("message", message)).toString()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message(message).body(body.toResponseBody("application/json".toMediaType())).build()
    }
}
