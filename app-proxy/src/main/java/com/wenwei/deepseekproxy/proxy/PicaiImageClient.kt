package com.wenwei.deepseekproxy.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * PicAI 生图客户端（picai.com，GPT Image 2 文生图，2026-10-08 实机链路验证）。
 *
 * 协议（逆向自 picai-relay，Python 版与本客户端同链路）：
 *  1. 提交：POST /api/fal/run，body {modelId:"gpt-image-2",
 *     input:{prompt, image_size, aspect_ratio, num_images, width, height,
 *     num_inference_steps:28}, libraryMeta:{prompt}} → {jobId}
 *  2. 轮询：GET /api/fal/jobs/{jobId}（约 3s 一次）→ succeeded 后
 *     outputs[] 里 kind=="image" 的 url
 *
 * "无限"机制（2026-10-08 对照实验实测）：
 *  - 上游游客额度按 X-Forwarded-For / X-Real-IP 头里的 IP 记账，每个新 IP
 *    首次生图免费放行，同 IP 第二次起 402 insufficient_credits；
 *  - 与 Cookie 无关（新 Cookie + 旧 IP 照样 402，实验组 C 验证）；
 *  - 因此【每次生图现算一个新随机公网 IPv4】写进这两个头，同一次生图的
 *    提交+轮询复用同一身份；402/429/403/5xx/连接故障换新身份重试。
 *
 * 关键限制：与 AppBMS 同理，机房/代理出口有被封风险，强制直连（Proxy.NO_PROXY），
 * 只保证手机流量/家庭宽带可用。出图 URL 为上游转存地址，有回收周期，客户端及时转存。
 */
object PicaiImageClient {
    private const val BASE = "https://picai.com"
    private const val UPSTREAM_MODEL = "gpt-image-2"
    private const val UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

    private const val POLL_INTERVAL_MS = 3000L
    private const val MAX_POLL = 60          // ≈3 分钟
    private const val IDENTITY_RETRIES = 4   // 额度类失败换新身份重试上限
    private const val MAX_POLL_JITTER = 5    // 轮询连接抖动容忍次数（同身份内）

    /** 上游只有一个生图模型；文生图由网关图片路由进入（图生图暂不接入） */
    val MODELS = listOf(UPSTREAM_MODEL)

    /** App resolveRatio 产出的比例串 → picai 的 (image_size, width, height)；aspect_ratio 直接用原串 */
    internal data class PicaiSize(val imageSize: String, val width: Int, val height: Int)

    internal val RATIO_MAP = mapOf(
        "1:1" to PicaiSize("square_hd", 1024, 1024),
        "9:16" to PicaiSize("portrait_16_9", 576, 1024),
        "16:9" to PicaiSize("landscape_16_9", 1024, 576),
        "4:3" to PicaiSize("landscape_4_3", 1152, 864),
        "3:4" to PicaiSize("portrait_4_3", 864, 1152),
    )

    private val client = OkHttpClient.Builder()
        // 上游对机房/代理出口不友好，必须直连；也避免本机系统代理劫持出口 IP
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 额度/限流/连接类失败 —— 换新身份重试有意义 */
    private class IdentityLimitedException(message: String) : RuntimeException(message)

    /**
     * 现算一个新伪造身份 IP（"无限"的钥匙）。
     * 随机公网 IPv4，排除回环/私有/保留/CGNAT/基准测试段。
     */
    internal fun mintIp(): String {
        while (true) {
            val a = (1..223).random()
            if (a == 10 || a == 127) continue
            val b = (0..255).random()
            if (a == 172 && b in 16..31) continue
            if (a == 192 && (b == 0 || b == 168)) continue
            if (a == 169 && b == 254) continue
            if (a == 100 && b in 64..127) continue
            if (a == 198 && (b == 18 || b == 19)) continue
            return "$a.$b.${(0..255).random()}.${(1..254).random()}"
        }
    }

    private fun baseHeaders(ip: String) = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json",
        "X-Forwarded-For" to ip,
        "X-Real-IP" to ip,
    )

    /** 执行请求并按状态码分类。IOException 原样抛出，由调用方决定在哪个层级处理。 */
    private fun callUpstream(req: Request): JSONObject {
        try {
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                when {
                    r.code == 402 || r.code == 429 || r.code == 403 ->
                        throw IdentityLimitedException("HTTP ${r.code}: ${text.take(150)}")
                    r.code >= 500 ->
                        throw IdentityLimitedException("HTTP ${r.code}: ${text.take(150)}")
                    r.code != 200 ->
                        throw RuntimeException("PicAI 上游拒绝: HTTP ${r.code} ${text.take(200)}")
                }
                return JSONObject(text)
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: IOException) {
            throw e
        }
    }

    private fun post(path: String, body: JSONObject, ip: String): JSONObject {
        val rb = Request.Builder().url(BASE + path)
        baseHeaders(ip).forEach { (k, v) -> rb.header(k, v) }
        rb.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        return try {
            callUpstream(rb.build())
        } catch (e: IOException) {
            // 提交阶段连接故障：换新身份重试是正确处理
            throw IdentityLimitedException("连接故障: ${e.message}")
        }
    }

    private fun get(path: String, ip: String): JSONObject {
        val rb = Request.Builder().url(BASE + path)
        baseHeaders(ip).forEach { (k, v) -> rb.header(k, v) }
        return callUpstream(rb.build())
    }

    /**
     * 文生图：提交任务并轮询到完成，返回图片直链列表。
     * ratio 取 resolveRatio 产出（"1:1"/"9:16"/…），未知比例回落 1:1。
     */
    fun generateImage(prompt: String, ratio: String, model: String): List<String> {
        var lastErr: Exception? = null
        for (attempt in 1..IDENTITY_RETRIES) {
            val ip = mintIp()   // 每次尝试全新身份——额度记账单位就是这个头里的 IP
            try {
                val urls = runOnce(prompt, ratio, model, ip)
                LogStore.i("PicaiImage", "生图成功: attempt=$attempt ratio=$ratio ip=$ip 张数=${urls.size}")
                return urls
            } catch (e: IdentityLimitedException) {
                lastErr = e
                LogStore.i("PicaiImage", "attempt#$attempt 身份不可用($ip)，换新身份重试: ${e.message}")
                Thread.sleep(2000)
            }
        }
        throw RuntimeException(
            "PicAI 连续 ${IDENTITY_RETRIES} 个身份均被上游拒绝（最后错误: ${lastErr?.message}）。" +
                "若持续出现，说明上游已收紧游客策略或当前出口 IP 被盯上，可切换网络后重试"
        )
    }

    /** 单次身份内：提交 → 轮询（同 IP 同会话语义，与实测通过的链路一致） */
    private fun runOnce(prompt: String, ratio: String, model: String, ip: String): List<String> {
        val size = RATIO_MAP[ratio] ?: RATIO_MAP.getValue("1:1")
        val body = JSONObject()
            .put("modelId", if (model.isBlank()) UPSTREAM_MODEL else model)
            .put(
                "input", JSONObject()
                    .put("prompt", prompt)
                    .put("image_size", size.imageSize)
                    .put("aspect_ratio", ratio)
                    .put("num_images", 1)
                    .put("width", size.width)
                    .put("height", size.height)
                    .put("num_inference_steps", 28)
            )
            .put("libraryMeta", JSONObject().put("prompt", prompt))

        val job = post("/api/fal/run", body, ip)
        val jobId = job.optString("jobId")
        if (jobId.isBlank()) {
            throw RuntimeException("PicAI 提交异常: ${job.toString().take(200)}")
        }
        LogStore.i("PicaiImage", "任务已提交: jobId=$jobId ratio=$ratio ip=$ip")

        var pollFails = 0
        for (i in 0 until MAX_POLL) {
            Thread.sleep(POLL_INTERVAL_MS)
            val st = try {
                get("/api/fal/jobs/$jobId", ip).also { pollFails = 0 }
            } catch (e: IOException) {
                // 轮询抖动：同身份内小重试，不浪费已提交的任务
                if (++pollFails > MAX_POLL_JITTER) {
                    throw IdentityLimitedException("轮询连续失败: ${e.message}")
                }
                LogStore.i("PicaiImage", "poll#$i 抖动，重试: ${e.message}")
                continue
            }
            when (st.optString("status")) {
                "succeeded" -> {
                    val arr = st.optJSONArray("outputs")
                        ?: throw RuntimeException("PicAI succeeded 但无 outputs")
                    val urls = (0 until arr.length()).mapNotNull { k ->
                        arr.optJSONObject(k)
                            ?.takeIf { it.optString("kind") == "image" }
                            ?.optString("url")
                            ?.takeIf { it.isNotBlank() }
                    }
                    if (urls.isEmpty()) throw RuntimeException("PicAI succeeded 但无图片输出")
                    return urls
                }
                "failed" -> throw RuntimeException("PicAI 任务失败: ${st.toString().take(200)}")
            }
        }
        throw RuntimeException("PicAI 任务超时（${MAX_POLL} 次轮询未完成）")
    }
}
