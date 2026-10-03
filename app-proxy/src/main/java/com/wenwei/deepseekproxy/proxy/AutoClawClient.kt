package com.wenwei.deepseekproxy.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * AutoClaw（智谱 autoglm 桌面端）客户端——把账号积分变成 OpenAI 兼容聊天 API。
 *
 * 协议（2026-10-02 mitmproxy 全链路抓包 + agent2api autoclaw Rust 源逐行核对）：
 *  1. 聊天：POST https://autoglm-acceleration-api.zhipuai.cn
 *     /autoclaw-proxy/proxy/autoclaw/chat/completions
 *     三重门：
 *     ① 认证头是 **X-Authorization**（不是 Authorization——用错 401 Invalid token）
 *     ② X-Request-Model = 路由ID（前缀决定计费通道），body.model = 剥前缀模型名
 *        zai_ → 按量积分；zaicoding_ → Coding Plan 会员通道
 *     ③ system 必须以 OpenClaw 身份句开头（无 system 或外来身份句 → 406 空体）
 *  2. 签名头（所有请求）：X-Auth-Sign = MD5("100003&{秒ts}&38d2391985e2369a5fb8227d8e6cd5e5")
 *     + X-Auth-Appid + X-Auth-TimeStamp + X-Version: 1.18.5
 *  3. 刷新：POST /userapi/v1/agent-refresh，body {refresh_token, source_id:"autoclaw",
 *     device_id}，响应 data.access_token + data.refresh_token（会轮换）。
 *     品牌头（X-Product/X-Client-Type/X-Harness-Type 等）必须带上否则 400002 签名失败。
 *  4. 余额：GET /agent-assetmgr/api/v2/wallets?biz_app_id=autoclaw（用 Authorization 头）
 *
 * 模型 4 个（路由ID → body名）：
 *   zai_auto → auto（后端 DeepSeek-V4-Pro）
 *   zai_auto-fast → auto-fast
 *   zaicoding_glm-5.3 → glm-5.3（Coding Plan 通道）
 *   zai_glm-5.3-flash → glm-5.3-flash（最便宜）
 */
object AutoClawClient {
    private const val BASE = "https://autoglm-acceleration-api.zhipuai.cn"
    private const val CHAT = "$BASE/autoclaw-proxy/proxy/autoclaw/chat/completions"
    private const val REFRESH = "$BASE/userapi/v1/agent-refresh"
    private const val SEND_CODE = "$BASE/userapi/v1/agent-send-code"
    private const val LOGIN = "$BASE/userapi/v1/agent-login"
    private const val WALLETS = "$BASE/agent-assetmgr/api/v2/wallets?biz_app_id=autoclaw"
    private const val APP_ID = "100003"
    private const val APP_KEY = "38d2391985e2369a5fb8227d8e6cd5e5"
    private const val VERSION = "1.18.5"

    /** system 前缀：上游 2026-09-22 闸门要求（身份句 + ## Tooling 段，缺一不可） */
    private val IDENTITY_PREFIX = (
        "You are a personal assistant running inside OpenClaw.\n\n" +
            "## Tooling\n" +
            "Available tools are policy-filtered. Names are case-sensitive; call exactly as listed.\n"
        )

    /** 路由ID → 剥前缀的 body 模型名 */
    private val MODEL_ROUTES = mapOf(
        "zai_auto" to "auto",
        "zai_auto-fast" to "auto-fast",
        "zaicoding_glm-5.3" to "glm-5.3",
        "zai_glm-5.3-flash" to "glm-5.3-flash",
    )

    @Volatile var accessToken: String = ""
    @Volatile var refreshToken: String = ""
    @Volatile var deviceId: String = UUID.randomUUID().toString()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)  // 深度思考可能很长
        // 强制 HTTP/1.1：上游对 OkHttp 协商的 HTTP/2 多路复用会话处理不稳
        // （2026-10-03 手机端 500 parse response failed 排查；curl h1/h2 均通，仅 OkHttp 失败）
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()

    fun isConfigured(): Boolean = accessToken.isNotBlank()

    fun applyConfig(json: String) {
        if (json.isBlank()) { accessToken = ""; refreshToken = ""; return }
        runCatching {
            val o = JSONObject(json)
            o.optString("access_token", "").takeIf { it.isNotBlank() }?.let { accessToken = it }
            o.optString("refresh_token", "").takeIf { it.isNotBlank() }?.let { refreshToken = it }
            o.optString("device_id", "").takeIf { it.isNotBlank() }?.let { deviceId = it }
        }
    }

    fun configJson(): String = JSONObject().apply {
        put("access_token", accessToken)
        put("refresh_token", refreshToken)
        put("device_id", deviceId)
    }.toString()

    private fun signHeaders(): List<Pair<String, String>> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sign = java.security.MessageDigest.getInstance("MD5")
            .digest("$APP_ID&$ts&$APP_KEY".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return listOf(
            "Content-Type" to "application/json",
            "X-Version" to VERSION,
            "X-Product" to "autoclaw",
            "X-Client-Type" to "pc",
            "X-Harness-Type" to "zcode",  // 管理接口（刷新/余额）必须带
            "X-Tm" to "linux",
            "X-Lang" to "zh-CN",
            "X-Channel" to "official",
            "X-Auth-Appid" to APP_ID,
            "X-Auth-TimeStamp" to ts,
            "X-Auth-Sign" to sign,
            "X-Trace-Id" to UUID.randomUUID().toString().replace("-", "").take(24),
        )
    }

    /**
     * 对话域专用头（参考 autoclaw-gw 参考实现 chat_headers，2026-10-02 406 根因修复）：
     * ① X-Version 用 1.17.8（DESKTOP_VERSION——上游按它走兼容路径，不是管理域的 1.18.5）
     * ② **绝不能带 X-Harness-Type**（带上 → 403/406——此前的 406 根因就是统一用了 signHeaders）
     * ③ UA 必须伪装桌面端（阿里云 WAF 拦 OkHttp 默认 UA）
     */
    private fun chatHeaders(routeId: String): List<Pair<String, String>> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sign = java.security.MessageDigest.getInstance("MD5")
            .digest("$APP_ID&$ts&$APP_KEY".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return listOf(
            "Content-Type" to "application/json",
            "Accept" to "*/*",
            "X-Authorization" to "Bearer $accessToken",
            "X-Request-Id" to UUID.randomUUID().toString().replace("-", ""),
            "X-Request-Model" to routeId,
            "X-Product" to "autoclaw",
            "X-Client-Type" to "pc",
            // 注意：对话域绝不能带 X-Harness-Type！
            "X-Tm" to "linux",
            "X-Version" to "1.17.8",
            "X-Lang" to "zh-CN",
            "X-Channel" to "official",
            "x_trace_id" to "autoclaw-desktop",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) AutoClaw/1.17.8 Chrome/120.0.0.0 Electron/28.0.0 Safari/537.36",
        )
    }

    /** 刷新 access token；refresh_token 会轮换（响应里的新值替换旧的） */
    @Synchronized
    fun refresh(): Boolean {
        if (refreshToken.isBlank()) return false
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .put("source_id", "autoclaw")
            .put("device_id", deviceId)
            .toString()
        val req = Request.Builder().url(REFRESH)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { signHeaders().forEach { (k, v) -> header(k, v) } }
            // 注意：不带 authorization 头。带上（哪怕旧 token）会被上游风控判定，
            // 签出的新 access_token 聊天端点拒收（2026-10-03 CF Worker 实测）；不带反而稳。
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (r.code != 200) {
                    LogStore.w("AutoClaw", "刷新 HTTP ${r.code}: ${r.body?.string()?.take(150)}")
                    return false
                }
                val j = JSONObject(r.body?.string() ?: "{}")
                val data = j.optJSONObject("data") ?: return false
                val newAccess = data.optString("access_token", "")
                val newRefresh = data.optString("refresh_token", "")
                if (newAccess.isNotBlank()) {
                    accessToken = newAccess
                    if (newRefresh.isNotBlank()) refreshToken = newRefresh
                    LogStore.i("AutoClaw", "token 已刷新")
                    true
                } else false
            }
        } catch (e: Exception) {
            LogStore.w("AutoClaw", "刷新异常: ${e.message}")
            false
        }
    }

    /**
     * 聊天（OpenAI 兼容）。入参用纯 Kotlin 数据（role→content 列表）。
     * messages 会做 system 前缀注入（上游闸门要求）。
     *
     * ⚠️ 历史坑（2026-10-03）：org.json 的 JSONArray.put(0, x) 是**替换**不是前插
     * （JS 的 unshift 才是前插），曾把用户消息原地覆盖成 system 消息 → 上游 500
     * parse response failed。所以这里按顺序构建：无 system 时先放身份句再放消息。
     */
    fun chatCompletion(routeId: String, msgs: List<Pair<String, String>>, maxTokens: Int? = null): okhttp3.Response {
        if (!isConfigured()) {
            throw IllegalStateException("AutoClaw 未配置：请在「接口」页登录或粘贴 token JSON")
        }
        val bodyModel = MODEL_ROUTES[routeId] ?: routeId.removePrefix("zaicoding_").removePrefix("zai_")
        val normalized = org.json.JSONArray()
        val systemRoles = setOf("system", "developer")
        var injected = false
        if (msgs.none { it.first in systemRoles }) {
            normalized.put(JSONObject().apply { put("role", "system"); put("content", IDENTITY_PREFIX) })
            injected = true
        }
        for ((role, content) in msgs) {
            val isSystem = role in systemRoles
            normalized.put(JSONObject().apply {
                put("role", role)
                put("content", if (isSystem && !injected) IDENTITY_PREFIX + content else content)
            })
            if (isSystem) injected = true
        }
        val openAiBody = JSONObject().apply {
            put("model", bodyModel)
            put("messages", normalized)
            put("stream", true)  // 上游只支持流式（非流式 → 500 parse response failed）
            maxTokens?.let { put("max_tokens", it) }
        }
        val req = Request.Builder().url(CHAT)
            .post(openAiBody.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                // 对话域用专用头：无 X-Harness-Type + X-Version=1.17.8 + 桌面端 UA
                // （用 signHeaders 会带 X-Harness-Type → 406，2026-10-02 实测修复）
                chatHeaders(routeId).forEach { (k, v) -> header(k, v) }
            }
            .build()
        var resp = client.newCall(req).execute()
        if (resp.code == 401 && refresh()) {
            resp.close()
            val retry = req.newBuilder()
                .header("X-Authorization", "Bearer $accessToken")
                .build()
            resp = client.newCall(retry).execute()
        }
        return resp
    }

    /** 余额（积分） */
    fun getBalance(): String {
        val req = Request.Builder().url(WALLETS)
            .get()
            .apply {
                signHeaders().forEach { (k, v) -> header(k, v) }
                header("authorization", "Bearer $accessToken")  // 余额用常规头
            }
            .build()
        client.newCall(req).execute().use { r ->
            if (r.code != 200) throw RuntimeException("余额 HTTP ${r.code}")
            val j = JSONObject(r.body?.string() ?: "{}")
            return j.optJSONObject("data")?.optInt("total_balance", -1)?.toString() ?: "?"
        }
    }

    // ============ 手机验证码登录（协议逆向自桌面端 main.cjs AuthApiAdapter，2026-10-03）============

    /** 登录结果：user_id + tokens（成功后已写入本对象的 accessToken/refreshToken） */
    data class LoginResult(val userId: String, val balance: String?)

    private fun postAdmin(url: String, body: JSONObject): Pair<Int, JSONObject> {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { signHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: "{}"
            return try { r.code to JSONObject(txt) } catch (e: Exception) {
                r.code to JSONObject().put("msg", txt.take(150))
            }
        }
    }

    /**
     * 发送验证码。手机号 11 位裸数字。
     * @return 成功返回 null；失败返回用户可读的错误信息。
     */
    fun sendCode(phone: String): String? {
        if (!Regex("^1\\d{10}$").matches(phone)) return "手机号格式不对（11 位数字）"
        val (code, j) = postAdmin(
            SEND_CODE,
            JSONObject().put("phone", phone).put("source_id", "autoclaw").put("device_id", deviceId)
        )
        if (code != 200) return "发送失败 HTTP $code（若开了 VPN 请关掉再试）"
        if (j.optInt("code", -1) != 0) return "发送失败：${j.optString("msg", "未知错误")}"
        val data = j.optJSONObject("data")
        if (data == null || !data.optBoolean("result", false)) return "发送失败：${j.optString("msg", "result=false")}"
        LogStore.i("AutoClaw", "验证码已发送至 $phone")
        return null
    }

    /**
     * 验证码登录：成功后 tokens 已写入本对象（调用方随后 configJson() 持久化）。
     * @return 成功返回 LoginResult；失败返回用户可读错误信息。
     */
    fun loginWithCode(phone: String, code: String): Result<LoginResult> {
        val codeNum = code.trim().toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("验证码是数字"))
        val (http, j) = postAdmin(
            LOGIN,
            JSONObject().put("phone", phone).put("code", codeNum)
                .put("source_id", "autoclaw").put("device_id", deviceId)
        )
        if (http != 200) return Result.failure(IllegalStateException("登录失败 HTTP $http（若开了 VPN 请关掉再试）"))
        if (j.optInt("code", -1) != 0) {
            return Result.failure(IllegalStateException("登录失败：${j.optString("msg", "验证码错误或已过期")}"))
        }
        val data = j.optJSONObject("data")
            ?: return Result.failure(IllegalStateException("登录响应异常"))
        val at = data.optString("access_token", "")
        val rt = data.optString("refresh_token", "")
        if (at.isBlank() || rt.isBlank()) {
            return Result.failure(IllegalStateException("登录响应缺少 token"))
        }
        accessToken = at.replace("Bearer ", "")
        refreshToken = rt.replace("Bearer ", "")
        val userId = data.optString("user_id", "")
        val balance = runCatching { getBalance() }.getOrNull()
        LogStore.i("AutoClaw", "手机验证码登录成功 user=$userId 余额=$balance")
        return Result.success(LoginResult(userId, balance))
    }
}
