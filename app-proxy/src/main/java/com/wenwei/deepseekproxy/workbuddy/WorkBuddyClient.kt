package com.wenwei.deepseekproxy.workbuddy

import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkBuddy plugin-token 协议客户端。
 *
 * 与官方 CLI 插件相同的授权流（逆向自 WorkBuddy2API，与官方 CLI 行为一致）：
 *
 *   1. POST /v2/plugin/auth/state?platform=CLI  -> { state, authUrl }
 *   2. 用户在 WebView 中打开 authUrl 完成网页登录
 *   3. GET  /v2/plugin/auth/token?state=<state> -> { accessToken, refreshToken, ... }
 *   4. POST /v2/plugin/auth/token/refresh       -> 换新 accessToken
 *   5. POST /v2/chat/completions                -> SSE 流式返回
 *
 * 注意：拿到的是 WorkBuddy 插件授权接口签发的 token，不是抓取的浏览器 cookie。
 */
object WorkBuddyClient {

    enum class Region(
        val displayName: String,
        val baseUrl: String,
        val domain: String,
        val language: String,
    ) {
        DOMESTIC(
            displayName = "国内版",
            baseUrl = "https://copilot.tencent.com",
            domain = "www.codebuddy.cn",
            language = "zh-CN",
        ),
        INTERNATIONAL(
            displayName = "国际版",
            baseUrl = "https://www.codebuddy.ai",
            domain = "www.codebuddy.ai",
            language = "en-US",
        ),
    }

    data class Credential(
        val accessToken: String,
        val refreshToken: String = "",
        val userId: String = "",
        val enterpriseId: String = "",
        val domain: String = "",
        val expiresAt: Long = 0L,
    ) {
        val usable: Boolean
            get() = accessToken.isNotBlank()

        fun expiringSoon(now: Long = System.currentTimeMillis()): Boolean =
            expiresAt > 0L && now >= expiresAt - REFRESH_MARGIN_MS
    }

    data class LoginSession(
        val state: String,
        val authUrl: String,
    )

    const val DEFAULT_UPSTREAM_MODEL = "default-model"
    private const val REFRESH_MARGIN_MS = 60_000L
    private const val CHAT_AGENT = "cli"
    private const val IDE_NAME = "WorkBuddy"
    private const val IDE_VERSION = "5.5.6"
    private const val UA_CLI = "WorkBuddy/5.5.6 WorkBuddy/5.5.6 CLI/2.137.1"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    fun fromGrant(json: JSONObject): Credential = Credential(
        accessToken = json.optString("accessToken"),
        refreshToken = json.optString("refreshToken"),
        userId = json.optString("userId").ifBlank { json.optString("uid") },
        enterpriseId = json.optString("enterpriseId"),
        domain = json.optString("domain"),
        expiresAt = json.optLong("expiresAt").takeIf { it > 0L }
            ?: json.optLong("expiresIn").let { expiresIn ->
                if (expiresIn > 0L) System.currentTimeMillis() + expiresIn * 1000L else 0L
            },
    )

    fun toJson(credential: Credential): JSONObject = JSONObject()
        .put("accessToken", credential.accessToken)
        .put("refreshToken", credential.refreshToken)
        .put("userId", credential.userId)
        .put("enterpriseId", credential.enterpriseId)
        .put("domain", credential.domain)
        .put("expiresAt", credential.expiresAt)

    /** 第 1 步：向服务器申请一次性授权会话，返回待打开的 [LoginSession.authUrl]。 */
    fun requestLoginSession(region: Region): LoginSession {
        val body = requireBody(
            url = region.baseUrl + "/v2/plugin/auth/state?platform=CLI",
            method = "POST",
            headers = grantHeaders(region),
            payload = JSONObject(),
        )
        val state = body.optString("state")
        val authUrl = body.optString("authUrl")
        if (state.isBlank() || authUrl.isBlank()) {
            throw IOException("WorkBuddy 登录接口未返回 state/authUrl")
        }
        return LoginSession(state = state, authUrl = authUrl)
    }

    /** 第 3 步：轮询授权结果。用户尚未完成登录时返回 null。 */
    fun pollGrant(region: Region, state: String): Credential? {
        val encoded = runCatching { URLEncoder.encode(state, "UTF-8") }.getOrDefault(state)
        val body = request(
            url = region.baseUrl + "/v2/plugin/auth/token?state=" + encoded,
            method = "GET",
            headers = grantHeaders(region),
            payload = null,
            allowMissing = true,
        ) ?: return null
        val credential = fromGrant(body)
        return credential.takeIf { it.usable }
    }

    /** 第 4 步：无感续期。 */
    fun refresh(region: Region, credential: Credential): Credential {
        val refreshToken = credential.refreshToken
        if (refreshToken.isBlank()) throw IOException("WorkBuddy 登录已失效，请重新登录")
        val headers = grantHeaders(region).toMutableMap().apply {
            put("X-Refresh-Token", refreshToken)
            put("X-Auth-Refresh-Source", "plugin")
        }
        val body = requireBody(
            url = region.baseUrl + "/v2/plugin/auth/token/refresh",
            method = "POST",
            headers = headers,
            payload = JSONObject(),
        )
        val rotated = fromGrant(body)
        if (!rotated.usable) throw IOException("刷新登录响应缺少 accessToken")
        return rotated.copy(
            refreshToken = rotated.refreshToken.ifBlank { refreshToken },
            domain = rotated.domain.ifBlank { credential.domain },
            userId = rotated.userId.ifBlank { credential.userId },
            enterpriseId = rotated.enterpriseId.ifBlank { credential.enterpriseId },
        )
    }

    /**
     * 第 5 步：流式对话调用。
     *
     * @param messages (role, content) 列表，role 为 system/user/assistant。
     * @param onDelta 每收到一段增量文本回调一次；返回总共发出的字符数。
     */
    suspend fun chatStream(
        region: Region,
        credential: Credential,
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        onDelta: suspend (String) -> Unit,
    ): Int {
        val payload = buildChatPayload(model, messages, temperature)
        val response = client.newCall(
            Request.Builder()
                .url(region.baseUrl + "/v2/chat/completions")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .apply {
                    chatHeaders(region, credential).forEach { (key, value) -> header(key, value) }
                }
                .build()
        ).execute()
        if (!response.isSuccessful) {
            val detail = runCatching { response.body?.string() }.getOrNull().orEmpty().take(160)
            val code = response.code
            response.close()
            if (code == 401 || code == 403) {
                throw WorkBuddyUnauthorizedException("WorkBuddy 对话失败 HTTP $code：$detail")
            }
            throw IOException("WorkBuddy 对话失败 HTTP $code：$detail")
        }
        return response.use { resp ->
            WorkBuddySse.parseStreaming(resp.body?.source(), onDelta).also { emitted ->
                if (emitted <= 0) throw IOException("WorkBuddy 未返回文本")
            }
        }
    }

    private fun buildChatPayload(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
    ): JSONObject {
        val array = JSONArray()
        messages.forEach { (role, content) ->
            if (content.isNotBlank()) {
                array.put(JSONObject().put("role", role).put("content", content))
            }
        }
        return JSONObject()
            .put("model", model.trim().ifBlank { DEFAULT_UPSTREAM_MODEL })
            .put("messages", array)
            .put("agent", CHAT_AGENT)
            .put("temperature", temperature.coerceIn(0.0, 1.0))
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
    }

    private fun grantHeaders(region: Region): Map<String, String> = linkedMapOf(
        "X-No-Authorization" to "1",
        "X-No-User-Id" to "1",
        "X-No-Enterprise-Id" to "1",
        "X-No-Department-Info" to "1",
        "X-Domain" to region.domain,
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "X-Requested-With" to "XMLHttpRequest",
        "X-CodeBuddy-Request" to "1",
        "Origin" to region.baseUrl,
        "Referer" to region.baseUrl + "/",
        "Accept-Language" to region.language,
        "User-Agent" to UA_CLI,
    )

    private fun chatHeaders(region: Region, credential: Credential): Map<String, String> =
        linkedMapOf<String, String>().apply {
            put("Authorization", "Bearer ${credential.accessToken}")
            put("Content-Type", "application/json")
            put("Accept", "text/event-stream")
            put("User-Agent", UA_CLI)
            put("X-Domain", credential.domain.ifBlank { region.domain })
            put("X-Product", "SaaS")
            if (credential.userId.isNotBlank()) put("X-User-Id", credential.userId)
            if (credential.enterpriseId.isNotBlank()) {
                put("X-Enterprise-Id", credential.enterpriseId)
                put("X-Tenant-Id", credential.enterpriseId)
            }
            put("X-Conversation-ID", conversationId())
            put("X-Request-ID", System.nanoTime().toString())
            put("X-Agent-Intent", "craft")
            put("X-Agent-Type", "main")
            put("X-IDE-Name", IDE_NAME)
            put("X-IDE-Type", IDE_NAME)
            put("X-IDE-Version", IDE_VERSION)
            put("x-codebuddy-request", "1")
        }

    private fun conversationId(): String =
        "wb2api-conv-" + UUID.randomUUID().toString().lowercase(Locale.ROOT)

    private fun requireBody(
        url: String,
        method: String,
        headers: Map<String, String>,
        payload: JSONObject?,
    ): JSONObject = request(
        url = url,
        method = method,
        headers = headers,
        payload = payload,
        allowMissing = false,
    ) ?: throw IOException("WorkBuddy 返回了空响应")

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        payload: JSONObject?,
        allowMissing: Boolean,
    ): JSONObject? {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val body = payload?.toString()?.toRequestBody(JSON_MEDIA)
        if (method == "GET") builder.get() else builder.post(body ?: "".toRequestBody(JSON_MEDIA))
        val response = client.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            val detail = runCatching { response.body?.string() }.getOrNull().orEmpty().take(160)
            val code = response.code
            response.close()
            if (allowMissing && (code == 401 || code == 403 || code == 404 || code == 409)) return null
            throw IOException("WorkBuddy 请求失败 HTTP $code：$detail")
        }
        val raw = response.use { it.body?.string().orEmpty() }
        if (raw.isBlank()) {
            if (allowMissing) return null
            throw IOException("WorkBuddy 返回了空响应")
        }
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
            ?: if (allowMissing) return null else throw IOException("WorkBuddy 返回了非 JSON 响应")
        return unwrap(parsed)
    }

    private fun unwrap(json: JSONObject): JSONObject {
        val data = json.opt("data")
        return when (data) {
            is JSONObject -> data
            else -> json
        }
    }
}

/** 401/403：调用方捕获后应先尝试 refresh，refresh 失败再提示重新登录。 */
class WorkBuddyUnauthorizedException(message: String) : IOException(message)
