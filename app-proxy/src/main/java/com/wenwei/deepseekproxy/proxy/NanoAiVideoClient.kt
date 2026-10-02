package com.wenwei.deepseekproxy.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NanoAI 视频客户端（nano-banana.leansoft-ai.com，双引擎：k_grok=可灵 Kling / v_fuse）。
 *
 * 协议逆向自 Nano AI 1.8.3(versionCode 83)，经 Python SDK 真机实测核对（2026-09-29）：
 *  1. 认证：X-Client-Auth = base64(AES-256-CBC("{ts}||com.nano.art.ai.generator||{ts+60}"))
 *     明文三段由 APK 内 JNI 拼接，60 秒有效期，每次请求重新生成
 *  2. 固定 8 头（APK NetworkModule.addHeaderRequest）：X-Client-Auth / device-id /
 *     app-user-id / app-version / version-code / language-code / app-id / os-type
 *     （没有 fcm-token 头，FCM token 是 createTask 的表单字段）
 *  3. 提交：POST /video/createTask（multipart：prompt / aspectRatio / image_prompt /
 *     model_type / is_regenerate / fcm_token / language_code；图生视频多一个 file
 *     字段，Content-Type 固定为 image 通配类型；纯文生不发 file 字段）
 *  4. 轮询：POST /video/checkTask（JSON：taskId / secretKey / accessKey / model_type /
 *     is_regenerate），data.state=success 后从 data.resultJson 解析 resultUrls[0]
 *
 * 限额与风控（实测结论）：
 *  - 额度按 device-id 记账 5 条/天、单设备并发 1 → 每次任务随机 device-id 刷新额度
 *  - 高频 createTask 触发上游 Cloudflare 1010 封 IP → 提交前固定 request_delay
 *  - 上游在 CF 防护后面，机房 IP 大概率被拒 → 强制 NO_PROXY 直连（同 AppBMS 策略），
 *    只适配手机流量/家庭宽带
 */
object NanoAiVideoClient {
    private const val BASE = "https://nano-banana.leansoft-ai.com"
    private const val PKG = "com.nano.art.ai.generator"
    private const val APP_VERSION = "1.8.3"
    private const val VERSION_CODE = "83"
    private const val UA = "okhttp/4.12.0"

    // AES-256-CBC 认证密钥对（云端 Remote Config 下发值，抓包 observed；APK 内不含）
    private const val AES_KEY = "dP8LmQ2zXw5KeHs9VrNaTc6YfBg4JuMi"
    private const val AES_IV = "F3qRwX7nKbV8mLpC"

    private const val REQUEST_DELAY_MS = 2000L
    private const val POLL_INTERVAL_MS = 5000L
    private const val MAX_POLL = 36   // ≈180s；实测可灵文生 ~36s 出片，图生更慢

    data class NanoVideoModel(
        val publicId: String,
        val displayName: String,
        val upstreamModelType: String
    )

    /** 对外模型 → 上游 model_type。publicId 必须含视频词元（kling/t2v），下游按词元归类视频模型 */
    val MODELS = listOf(
        NanoVideoModel("nanoai-kling", "可灵视频", "k_grok"),
        NanoVideoModel("nanoai-seedance-t2v", "Seedance 视频", "v_fuse"),
    )

    /**
     * 玩法模板（逆向自上游云端模板库 CDN JSON，2026-09-26 版快照，共 27 个）。
     * 语义（2026-09-29 实测确认）：
     *  - createTask 的 prompt 填 videoPrompt（服务端模板基础键，如 video_motion / video_elsa）
     *  - createTask 的 image_prompt 填 imagePrompt（官方效果子键，如 nano_rain；可为空）
     *    ——该字段同时接受自由描述文本，且会与子键叠加生效（实测"nano_rain, 描述"与
     *    "video_elsa + 纯描述"都正常出片），所以模板模式下用户描述走这个字段
     *  - 模板自带引擎（modelType），与用户当前选的模型无关，模板优先
     */
    data class NanoTemplate(
        val category: String,
        val styleName: String,
        val videoPrompt: String,
        val imagePrompt: String,
        val modelType: String,
        val thumbUrl: String
    )

    val TEMPLATES: List<NanoTemplate> = listOf(
        // Trending 热门
        NanoTemplate("Trending", "Hairswipe 2", "video_hairswipe_2", "", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_hairswipe.webp"),
        NanoTemplate("Trending", "Elsa", "video_elsa", "", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_elsa.webp"),
        NanoTemplate("Trending", "Pet Dance", "video_motion", "nano_pet_dance", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/dog_dance.webp"),
        NanoTemplate("Trending", "Pet Cook", "video_motion", "nano_pet_cook", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/cat.webp"),
        NanoTemplate("Trending", "Dance", "video_motion", "nano_dance", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/light_off.webp"),
        NanoTemplate("Trending", "New Year", "video_motion", "nano_new_year", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_newyear.webp"),
        NanoTemplate("Trending", "Rain", "video_motion", "nano_rain", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_rain.webp"),
        NanoTemplate("Trending", "Creepy", "video_motion", "nano_creepy", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_creepy2.webp"),
        NanoTemplate("Trending", "Stranger Things", "video_motion", "nano_stranger_things", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_stranger_things.webp"),
        // Hot 火热
        NanoTemplate("Hot", "Hollywood", "video_motion", "nano_hollywood", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_hollywood.webp"),
        NanoTemplate("Hot", "Couple Rain", "video_motion", "nano_couple_rain", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_couple_rain.webp"),
        NanoTemplate("Hot", "Frost", "video_motion", "nano_frost", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_frost.webp"),
        NanoTemplate("Hot", "Glacier", "video_motion", "nano_glacier", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_glacier.webp"),
        NanoTemplate("Hot", "Snow Globe", "video_motion", "nano_snow_globe", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_snow_globe.webp"),
        // Vintage 复古
        NanoTemplate("Vintage", "Felices", "video_motion", "nano_felices", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_felices.webp"),
        NanoTemplate("Vintage", "Beach", "video_motion", "nano_beach", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_beach.webp"),
        NanoTemplate("Vintage", "Flower", "video_motion", "nano_flower", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_flower.webp"),
        NanoTemplate("Vintage", "Rose1", "video_motion", "nano_rose1", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_rose1.webp"),
        NanoTemplate("Vintage", "Hongkong", "video_motion", "nano_hongkong", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_hongkong.webp"),
        // Arrest 短剧
        NanoTemplate("Arrest", "Home Arrest", "video_motion", "nano_home_arrest", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_home_arrest.webp"),
        NanoTemplate("Arrest", "Police Arrest", "video_motion", "nano_police_arrest", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_police_arrest.webp"),
        NanoTemplate("Arrest", "Courtroom Arrest", "video_motion", "nano_courtroom_arrest", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_courtroom_arrest.webp"),
        NanoTemplate("Arrest", "FBI Arrest", "video_motion", "nano_fbi_arrest", "v_fuse", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/video_fbi_arrest.webp"),
        // Viral Dance 爆款舞蹈（可灵引擎）
        NanoTemplate("Viral Dance", "Baby Dance 1", "video_baby_dance_1", "", "k_grok", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/baby_hyeri.webp"),
        NanoTemplate("Viral Dance", "Baby Dance 2", "video_baby_dance_2", "", "k_grok", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/kling_20260115_Motion_Control__4289_0.webp"),
        NanoTemplate("Viral Dance", "Baby Dance 3", "video_baby_dance_3", "", "k_grok", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/kling_20260115_Motion_Control__4436_0.webp"),
        NanoTemplate("Viral Dance", "Baby Dance 4", "video_baby_dance_4", "", "k_grok", "https://cdn.leansoft-ai.com/nano-ai/video-thumbs/kling_20260115_Motion_Control_Baby_dance_4104_0.webp"),
    )

    /** 按模板名（style_name）查模板 */
    fun findTemplate(styleName: String): NanoTemplate? =
        TEMPLATES.firstOrNull { it.styleName.equals(styleName, ignoreCase = true) }

    private val client = OkHttpClient.Builder()
        // 上游经 CF 防护，机房/代理 IP 会被拒；也避免系统代理劫持出口 IP
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** X-Client-Auth：base64(AES-256-CBC("{ts}||{pkg}||{ts+60}"))，60s 有效 */
    private fun buildAuth(): String {
        val ts = System.currentTimeMillis() / 1000
        val plain = "$ts||$PKG||${ts + 60}"
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun headers(): Map<String, String> = linkedMapOf(
        "X-Client-Auth" to buildAuth(),
        "device-id" to currentDeviceId,
        "app-user-id" to "",
        "app-version" to APP_VERSION,
        "version-code" to VERSION_CODE,
        "language-code" to "en",
        "app-id" to PKG,
        "os-type" to "android",
        "User-Agent" to UA,
    )

    private var currentDeviceId: String = randomDeviceId()

    /** APK 用 ANDROID_ID（16 位 hex）；随机轮换刷新 5 条/天的设备额度 */
    private fun randomDeviceId(): String {
        val hex = "0123456789abcdef"
        return (1..16).map { hex.random() }.joinToString("")
    }

    private fun Request.Builder.applyHeaders(h: Map<String, String>): Request.Builder {
        h.forEach { (k, v) -> header(k, v) }
        return this
    }

    /**
     * 生成视频，返回成片直链。imageBytes 为空 → 文生视频（不发 file 字段，对齐 APK 纯文字模式）。
     * ratio 仅支持 9:16 / 16:9 / 1:1。
     * imagePrompt：createTask 的 image_prompt 通道——模板模式下放官方效果子键(+用户描述)，
     * 普通模式留空。
     */
    fun generateVideo(
        prompt: String,
        ratio: String,
        upstreamModelType: String,
        imageBytes: ByteArray? = null,
        imageExt: String = "png",
        imagePrompt: String = "",
    ): String {
        currentDeviceId = randomDeviceId()
        Thread.sleep(REQUEST_DELAY_MS)   // 防 1010：createTask 前固定间隔

        // 1) createTask（multipart 表单）
        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("aspectRatio", ratio)
            .addFormDataPart("image_prompt", imagePrompt)
            .addFormDataPart("model_type", upstreamModelType)
            .addFormDataPart("is_regenerate", "false")
            .addFormDataPart("fcm_token", "")
            .addFormDataPart("language_code", "en")
        if (imageBytes != null) {
            mp.addFormDataPart(
                "file", "upload.$imageExt",
                imageBytes.toRequestBody("image/*".toMediaType())
            )
        }
        val createReq = Request.Builder()
            .url("$BASE/video/createTask")
            .applyHeaders(headers())
            .post(mp.build())
            .build()

        val taskId: String = client.newCall(createReq).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 403) {
                throw RuntimeException("NanoAI 拒绝了当前网络（403）：上游只接受住宅/手机流量 IP，请切换网络后重试")
            }
            val json = if (r.code == 200) runCatching { JSONObject(text) }.getOrNull() else null
            val taskId = json?.optJSONObject("data")?.optString("taskId").orEmpty()
            if (taskId.isBlank()) {
                throw RuntimeException("NanoAI createTask 失败: HTTP ${r.code} ${text.take(200)}")
            }
            taskId
        }
        LogStore.i(
            "NanoAiVideo",
            "任务已提交: taskId=$taskId model=$upstreamModelType ratio=$ratio 图生=${imageBytes != null}"
        )

        // 2) 轮询 checkTask（auth 60s 过期，每轮重新生成请求头）
        repeat(MAX_POLL) { _ ->
            Thread.sleep(POLL_INTERVAL_MS)
            val pollReq = Request.Builder()
                .url("$BASE/video/checkTask")
                .applyHeaders(headers())
                .post(
                    JSONObject()
                        .put("taskId", taskId)
                        .put("secretKey", "")
                        .put("accessKey", "")
                        .put("model_type", upstreamModelType)
                        .put("is_regenerate", false)
                        .toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .build()
            client.newCall(pollReq).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code != 200) {
                    LogStore.w("NanoAiVideo", "轮询 HTTP ${r.code}: ${text.take(120)}")
                    return@use
                }
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return@use
                val data = json.optJSONObject("data") ?: return@use
                when (data.optString("state")) {
                    "success" -> {
                        val resultJson = data.optString("resultJson")
                        val url = runCatching {
                            JSONObject(resultJson).optJSONArray("resultUrls")?.optString(0)
                        }.getOrNull()
                        if (!url.isNullOrBlank()) return url
                        throw RuntimeException("NanoAI 任务完成但未解析到视频: ${resultJson.take(200)}")
                    }
                    "fail" -> throw RuntimeException(
                        "NanoAI 任务失败: ${data.optString("resultJson").ifBlank { text.take(200) }}"
                    )
                }
            }
        }
        throw RuntimeException("NanoAI 任务超时（${MAX_POLL} 次轮询未完成）")
    }

    /**
     * 从视频请求体归一化宽高比：收 ratio / ratio_str / aspect_ratio / size（App 内视频页
     * 传 ratio_str，取值 9:16/3:4/1:1/16:9/4:3/2:5/5:2 或 Auto），统一折算为上游支持的
     * 9:16 / 16:9 / 1:1。
     */
    fun normalizeRatio(o: JSONObject): String {
        val raw = listOf("ratio", "ratio_str", "aspect_ratio", "size")
            .firstNotNullOfOrNull { k -> o.optString(k, "").takeIf { it.isNotBlank() } }
            ?.lowercase()?.trim().orEmpty()
        if (raw == "16:9" || raw == "4:3" || raw == "5:2") return "16:9"
        if (raw == "1:1") return "1:1"
        if (raw == "9:16" || raw == "3:4" || raw == "2:5") return "9:16"
        Regex("(\\d+)\\s*[x×*]\\s*(\\d+)").find(raw)?.let { m ->
            val w = m.groupValues[1].toDouble()
            val h = m.groupValues[2].toDouble()
            if (w > 0 && h > 0) {
                val r = w / h
                return when {
                    r > 1.2 -> "16:9"
                    r < 0.83 -> "9:16"
                    else -> "1:1"
                }
            }
        }
        return "9:16"
    }

    /** 图生视频参考图兜底：支持 http(s) 直链下载（data URI 由调用方先解 base64） */
    fun fetchImageBytes(url: String): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }
    }.getOrNull()
}
