package com.wenwei.deepseekproxy.proxy

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * PicAI 生图客户端（picai.com，GPT Image 2 文生图）。
 *
 * 协议（逆向自 picai-relay，2026-10-08 实机验证）：
 *  1. 提交：POST /api/fal/run，body {modelId:"gpt-image-2",
 *     input:{prompt, image_size, aspect_ratio, num_images, width, height,
 *     num_inference_steps:28}, libraryMeta:{prompt}} → {jobId}，
 *     响应 set-cookie picai_sid=…（会话标识）
 *  2. 轮询：GET /api/fal/jobs/{jobId}（约 3s 一次，上限 60 次）→
 *     status == "succeeded" 后取 outputs[] 中 kind == "image" 的 url
 *
 * "无限"机制（2026-10-08 对照实验 + App 内 -v 抓包实锤）：
 *  - 游客额度按 X-Forwarded-For / X-Real-IP 头里的 IP 记账，每个新 IP
 *    首次生图免费放行，同 IP 第二次起 402 insufficient_credits；
 *  - 【关键】轮询接口校验任务归属：必须带创建任务那个会话的 picai_sid
 *    Cookie，否则 {"error":"forbidden"}（这就是"要 refresh 新 cookie"的
 *    真正含义——新身份拿到新 cookie，且该会话内复用它）；
 *  - 因此每个身份 = 新随机公网 IPv4（XFF 头）+ 独立 CookieJar（提交存、
 *    轮询带）；402/429/403/5xx/连接故障换新身份重试。
 *
 * 传输栈（真机排障结论）：主传输 exec 系统自带 /system/bin/curl
 * （真机实测可过），OkHttp 作 curl 不可用时的兜底。每身份一个独立
 * CookieJar 文件，用完即删。
 *
 * 关键限制：出图 URL 为上游转存地址，有回收周期，客户端及时转存；
 * 上游若收紧游客策略（持续 402/429/403），属上游变更非本服务故障。
 */
object PicaiImageClient {
    private const val BASE = "https://picai.com"
    private const val UPSTREAM_MODEL = "gpt-image-2"
    private const val UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
    private const val CURL_BIN = "/system/bin/curl"
    private const val APP_CACHE_DIR = "/data/user/0/com.wenwei.deepseekproxy.multiweb/cache"

    private const val POLL_INTERVAL_MS = 3000L
    private const val MAX_POLL = 60          // ≈3 分钟
    private const val IDENTITY_RETRIES = 4   // 额度类失败换新身份重试上限
    private const val MAX_POLL_JITTER = 5    // 轮询连接抖动容忍次数（同身份内）
    private const val CURL_SUBMIT_TIMEOUT_S = 90
    private const val CURL_POLL_TIMEOUT_S = 40

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

    /** 一个伪造身份：XFF 头里的 IP + 本会话 CookieJar 文件（提交存、轮询带） */
    internal class PicaiSession(val ip: String, val jarPath: String)

    /** 额度/限流/连接类失败 —— 换新身份重试有意义 */
    private class IdentityLimitedException(message: String) : RuntimeException(message)

    /** 现算一个新伪造身份 IP（"无限"的钥匙）。排除回环/私有/保留/CGNAT/基准测试段。 */
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

    private fun cookieJarPath(attempt: Int): String =
        "$APP_CACHE_DIR/picai_jar_${System.currentTimeMillis()}_${attempt}.txt"

    // ============ 传输栈：系统 curl 主栈 + OkHttp 兜底 ============

    @Volatile private var curlProbe: Boolean? = null

    /** 探测一次系统 curl 是否可用（探测结果缓存） */
    private fun curlBin(): String? {
        curlProbe?.let { return if (it) CURL_BIN else null }
        return try {
            val p = ProcessBuilder(CURL_BIN, "--version").start()
            val finished = p.waitFor(5, TimeUnit.SECONDS)
            val ok = finished && p.inputStream.bufferedReader().readText().contains("curl")
            p.destroy()
            curlProbe = ok
            if (ok) CURL_BIN else null
        } catch (t: Throwable) {
            curlProbe = false
            LogStore.i("PicaiImage", "系统 curl 不可用，回落 OkHttp: ${t.javaClass.simpleName}")
            null
        }
    }

    /** 执行请求返回 (httpCode, bodyText)。IOException 表示连接层故障，由调用方分级处理。 */
    private fun execRequest(method: String, path: String, session: PicaiSession, bodyBytes: ByteArray?): Pair<Int, String> {
        val bin = curlBin()
        return if (bin != null) {
            curlExec(bin, method, path, session, bodyBytes)
        } else {
            okhttpExec(method, path, session, bodyBytes)
        }
    }

    /**
     * 通过系统 curl 发请求。不走 shell（ProcessBuilder 直 exec，参数逐个传入，
     * body 含引号/换行也安全）。curl 末行输出 %{http_code}，其余为响应体。
     * -c/-b 挂本会话 CookieJar：提交响应的 picai_sid 存入，轮询自动携带。
     */
    private fun curlExec(bin: String, method: String, path: String, session: PicaiSession, bodyBytes: ByteArray?): Pair<Int, String> {
        val timeoutS = if (bodyBytes != null) CURL_SUBMIT_TIMEOUT_S else CURL_POLL_TIMEOUT_S
        val args = mutableListOf(
            bin, "-s", "-S", "--max-time", timeoutS.toString(),
            "-w", "\n%{http_code}", "-X", method,
            "-c", session.jarPath,
            "-b", session.jarPath,
            "-H", "User-Agent: $UA",
            "-H", "Accept: application/json",
            "-H", "X-Forwarded-For: ${session.ip}",
            "-H", "X-Real-IP: ${session.ip}",
        )
        if (bodyBytes != null) {
            args += "-H"; args += "Content-Type: application/json"
            args += "--data-binary"; args += String(bodyBytes, Charsets.UTF_8)
        }
        args += BASE + path

        val proc = ProcessBuilder(args).start()
        val finished = proc.waitFor(200, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IOException("curl 超时")
        }
        if (proc.exitValue() != 0) {
            throw IOException("curl exit=${proc.exitValue()}")
        }
        val out = proc.inputStream.bufferedReader().readText()
        val idx = out.lastIndexOf('\n')
        val code = out.substring(idx + 1).trim().toIntOrNull() ?: -1
        val body = if (idx >= 0) out.substring(0, idx) else ""
        return code to body
    }

    /** OkHttp 兜底栈（Conscrypt 指纹，上游可能仍 403——保底可用性） */
    private val okhttpFallback = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 兜底栈按会话挂内存 CookieJar（语义同 curl 的 -c/-b） */
    private fun okhttpClientFor(session: PicaiSession): OkHttpClient {
        val store = mutableListOf<Cookie>()
        return okhttpFallback.newBuilder()
            .cookieJar(object : CookieJar {
                @Synchronized
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
                    store.addAll(cookies)
                }

                @Synchronized
                override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> = store.toList()
            })
            .build()
    }

    private fun okhttpExec(method: String, path: String, session: PicaiSession, bodyBytes: ByteArray?): Pair<Int, String> {
        val client = okhttpClientFor(session)
        val rb = Request.Builder().url(BASE + path)
        rb.header("User-Agent", UA)
        rb.header("Accept", "application/json")
        rb.header("X-Forwarded-For", session.ip)
        rb.header("X-Real-IP", session.ip)
        if (bodyBytes != null) {
            rb.post(bodyBytes.toRequestBody("application/json".toMediaType()))
        } else {
            rb.get()
        }
        client.newCall(rb.build()).execute().use { r ->
            return r.code to (r.body?.string().orEmpty())
        }
    }

    // ============ 统一分类 ============

    private fun post(path: String, body: JSONObject, session: PicaiSession): JSONObject {
        val (code, text) = try {
            execRequest("POST", path, session, body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            // 提交阶段连接故障：换新身份重试是正确处理
            throw IdentityLimitedException("连接故障: ${e.message}")
        }
        return classify(code, text)
    }

    private fun get(path: String, session: PicaiSession): JSONObject {
        val (code, text) = execRequest("GET", path, session, null)
        return classify(code, text)
    }

    /** 按状态码分类并解析 JSON */
    private fun classify(code: Int, text: String): JSONObject {
        when {
            code == 402 || code == 429 || code == 403 ->
                throw IdentityLimitedException("HTTP $code: ${text.take(150)}")
            code >= 500 ->
                throw IdentityLimitedException("HTTP $code: ${text.take(150)}")
            code != 200 ->
                throw RuntimeException("PicAI 上游拒绝: HTTP $code ${text.take(200)}")
        }
        return JSONObject(text)
    }

    // ============ 业务编排 ============

    /**
     * 文生图：提交任务并轮询到完成，返回图片直链列表。
     * ratio 取 resolveRatio 产出（"1:1"/"9:16"/…），未知比例回落 1:1。
     */
    fun generateImage(prompt: String, ratio: String, model: String): List<String> {
        var lastErr: Exception? = null
        for (attempt in 1..IDENTITY_RETRIES) {
            val session = PicaiSession(mintIp(), cookieJarPath(attempt))   // 每次尝试全新身份+独立CookieJar
            try {
                val urls = runOnce(prompt, ratio, model, session)
                LogStore.i("PicaiImage", "生图成功: attempt=$attempt ratio=$ratio ip=${session.ip} 张数=${urls.size}")
                return urls
            } catch (e: IdentityLimitedException) {
                lastErr = e
                LogStore.i("PicaiImage", "attempt#$attempt 身份不可用(${session.ip})，换新身份重试: ${e.message}")
                Thread.sleep(2000)
            } finally {
                java.io.File(session.jarPath).delete()
            }
        }
        throw RuntimeException(
            "PicAI 连续 ${IDENTITY_RETRIES} 个身份均被上游拒绝（最后错误: ${lastErr?.message}）。" +
                "若持续出现，说明上游已收紧游客策略或当前出口 IP 被盯上，可切换网络后重试"
        )
    }

    /** 单次身份内：提交 → 轮询（同一身份同一 Cookie 会话，与实测通过的链路一致） */
    private fun runOnce(prompt: String, ratio: String, model: String, session: PicaiSession): List<String> {
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

        val job = post("/api/fal/run", body, session)
        val jobId = job.optString("jobId")
        if (jobId.isBlank()) {
            throw RuntimeException("PicAI 提交异常: ${job.toString().take(200)}")
        }
        LogStore.i("PicaiImage", "任务已提交: jobId=$jobId ratio=$ratio ip=${session.ip}")

        var pollFails = 0
        for (i in 0 until MAX_POLL) {
            Thread.sleep(POLL_INTERVAL_MS)
            val st = try {
                get("/api/fal/jobs/$jobId", session).also { pollFails = 0 }
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
