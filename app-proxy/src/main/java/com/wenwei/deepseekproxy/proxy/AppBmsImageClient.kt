package com.wenwei.deepseekproxy.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * AppBMS 生图客户端（api.appbms.com，46 个模型）。
 *
 * 协议（逆向自 appbms_gateway.py）：
 *  1. POST /v1/device-sessions/challenges 拿挑战
 *  2. 生成 ECDSA P-256 密钥对，POST /v1/device-sessions 换 deviceSessionId
 *  3. 之后每个请求带签名头：X-Device-Session-Id / X-Request-Timestamp /
 *     X-Request-Nonce / X-Body-SHA256 / X-Device-Signature（ECDSA-SHA256, DER）
 *  4. POST /v1/ai/ai-image-gen 提交任务，按 statusUrl 轮询到 completed
 *
 * 关键限制：上游封禁机房/代理 IP（403），本客户端强制直连（Proxy.NO_PROXY），
 * 只适配手机流量或家庭宽带。手机端这就是天然优势。
 */
object AppBmsImageClient {
    private const val BASE = "https://api.appbms.com"
    private const val APP_ID = "com.photoeditor.remakemefaceswapaigenerator"
    private const val UA = "okhttp/4.12.0"
    private const val APP_VERSION = "1.3.2"
    private const val MAX_POLL = 60

    /** 上游 46 个生图模型（与 appbms 网关 MODELS 对齐） */
    val MODELS = listOf(
        "stable-diffusion", "sdxl", "sd-turbo", "sd3", "flux", "flux-pro", "flux-dev",
        "dall-e-3", "midjourney", "realistic", "realistic-vision", "photo", "portrait",
        "landscape", "anime", "cartoon", "art", "3d", "oil-painting", "watercolor",
        "digital-art", "cyberpunk", "fantasy", "abstract", "minimalist", "pixel-art",
        "vector", "sketch", "claymation", "figurine", "pixar", "disney", "comic",
        "manga", "chibi", "ghibli", "dream-shaper", "anything-v5", "openjourney",
        "seedream-5-pro", "seedream-5.0", "seedream-4.5", "nano-banana-pro",
        "nano-banana-2", "banana-2-lite", "gpt-image-2",
    )

    private val client = OkHttpClient.Builder()
        // 上游封代理/机房 IP，必须直连；也避免本机系统代理劫持导致 403
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile private var sessionId: String? = null
    private var keyPair: KeyPair? = null

    private fun b64url(b: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun JSONObject.toBytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)

    /** 会话建立阶段的无签名请求 */
    private fun postUnsigned(path: String, body: JSONObject, expect: IntArray): JSONObject {
        val req = Request.Builder()
            .url(BASE + path)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .post(body.toBytes().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code !in expect) {
                throw RuntimeException("AppBMS $path 失败: HTTP ${r.code} ${text.take(200)}")
            }
            return JSONObject(text)
        }
    }

    @Synchronized
    private fun ensureSession() {
        if (sessionId != null && keyPair != null) return
        val instId = UUID.randomUUID().toString()
        val chal = postUnsigned("/v1/device-sessions/challenges", JSONObject()
            .put("platform", "android")
            .put("appIdentifier", APP_ID)
            .put("packageName", APP_ID)
            .put("installationId", instId)
            .put("appVersion", APP_VERSION), intArrayOf(201))
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val kp = kpg.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"
        val sess = postUnsigned("/v1/device-sessions", JSONObject()
            .put("platform", "android")
            .put("appIdentifier", APP_ID)
            .put("packageName", APP_ID)
            .put("challengeId", chal.getString("challengeId"))
            .put("challenge", chal.getString("challenge"))
            .put("publicKeyPem", pem)
            .put("installationId", instId)
            .put("appVersion", APP_VERSION), intArrayOf(200, 201))
        keyPair = kp
        sessionId = sess.getString("deviceSessionId")
        LogStore.i("AppBmsImage", "会话建立: $sessionId")
    }

    private fun signHeaders(method: String, path: String, body: ByteArray): Map<String, String> {
        val sid = sessionId ?: throw IllegalStateException("AppBMS 会话未建立")
        val kp = keyPair ?: throw IllegalStateException("AppBMS 密钥未就绪")
        val ts = System.currentTimeMillis().toString()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.let(::b64url)
        val bh = b64url(MessageDigest.getInstance("SHA-256").digest(body))
        val payload = "$method\n$path\n$ts\n$nonce\n$sid\n$bh"
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        sig.update(payload.toByteArray(Charsets.UTF_8))
        return mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json",
            "X-Device-Session-Id" to sid,
            "X-Request-Timestamp" to ts,
            "X-Request-Nonce" to nonce,
            "X-Body-SHA256" to bh,
            "X-Device-Signature" to b64url(sig.sign()),
            "X-Signed-Recovery-Version" to "1",
        )
    }

    private fun execSigned(method: String, path: String, body: ByteArray): Pair<Int, String> {
        val headers = signHeaders(method, path, body)
        val builder = Request.Builder().url(BASE + path)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (method == "GET") {
            builder.get()
        } else {
            builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        client.newCall(builder.build()).execute().use { r ->
            return r.code to r.body?.string().orEmpty()
        }
    }

    /** 文生图：提交任务并轮询到完成，返回图片直链列表 */
    fun generateImage(prompt: String, ratio: String, model: String): List<String> {
        ensureSession()
        val path = "/v1/ai/ai-image-gen"
        val body = JSONObject()
            .put("imagePathList", JSONArray())
            .put("prompt", prompt)
            .put("imageRatio", ratio)
            .put("model", model)
            .toBytes()
        var (code, text) = execSigned("POST", path, body)
        if (code == 401) {
            // 会话过期：重建一次再试
            sessionId = null
            keyPair = null
            ensureSession()
            val retry = execSigned("POST", path, body)
            code = retry.first; text = retry.second
        }
        if (code == 403) {
            throw RuntimeException("AppBMS 拒绝了当前网络（403）：上游只接受住宅/手机流量 IP，请切换网络后重试")
        }
        if (code != 200 && code != 202) {
            throw RuntimeException("AppBMS 提交失败: HTTP $code ${text.take(200)}")
        }
        val data = JSONObject(text)
        val jobId = data.getString("jobId")
        var statusPath = data.optString("statusUrl", "").ifBlank { "$path/$jobId" }
        if (statusPath.startsWith(BASE)) statusPath = statusPath.removePrefix(BASE)
        LogStore.i("AppBmsImage", "任务已提交: jobId=$jobId model=$model ratio=$ratio")

        repeat(MAX_POLL) { i ->
            val (pcode, ptext) = execSigned("GET", statusPath, ByteArray(0))
            if (pcode == 403) {
                throw RuntimeException("AppBMS 轮询被拒（403）：当前网络 IP 被上游封禁")
            }
            if (pcode == 200) {
                val st = JSONObject(ptext)
                when (st.optString("state")) {
                    "completed" -> {
                        val arr = st.optJSONObject("result")?.optJSONArray("imageUrls") ?: JSONArray()
                        return (0 until arr.length()).map { arr.getString(it) }
                    }
                    "failed", "cancelled", "expired" -> {
                        throw RuntimeException("AppBMS 任务失败: ${st.optString("failedReason").ifBlank { ptext.take(200) }}")
                    }
                }
                val waitMs = st.optLong("pollAfterMs", 3500L).coerceAtLeast(1000L)
                Thread.sleep(waitMs)
            } else {
                Thread.sleep(3500L)
            }
        }
        throw RuntimeException("AppBMS 任务超时（${MAX_POLL} 次轮询未完成）")
    }
}
