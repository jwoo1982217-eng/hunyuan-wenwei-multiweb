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
            "X-Harness-Type" to "zcode",
            "X-Tm" to "linux",
            "X-Lang" to "zh-CN",
            "X-Channel" to "official",
            "X-Auth-Appid" to APP_ID,
            "X-Auth-TimeStamp" to ts,
            "X-Auth-Sign" to sign,
            "X-Trace-Id" to UUID.randomUUID().toString().replace("-", "").take(24),
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
            .apply { signHeaders().forEach { (k, v) -> header(k, v) }
                header("authorization", "Bearer $accessToken") }
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
     * 聊天（OpenAI 兼容透传）。返回上游 Response（调用方负责关闭）。
     * messages 会做 system 前缀注入（上游闸门要求）。
     */
    fun chatCompletion(routeId: String, openAiBody: JSONObject): okhttp3.Response {
        if (!isConfigured()) {
            throw IllegalStateException("AutoClaw 未配置：请在「接口」页粘贴 token JSON（含 access_token / refresh_token）")
        }
        val bodyModel = MODEL_ROUTES[routeId] ?: routeId.removePrefix("zaicoding_").removePrefix("zai_")
        val messages = openAiBody.optJSONArray("messages") ?: org.json.JSONArray()
        // system 前缀注入：首条 system 前插入身份句（上游闸门）
        val normalized = org.json.JSONArray()
        var systemDone = false
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (!systemDone && m.optString("role") in setOf("system", "developer")) {
                normalized.put(JSONObject().apply {
                    put("role", m.optString("role"))
                    put("content", IDENTITY_PREFIX + m.optString("content"))
                })
                systemDone = true
            } else {
                normalized.put(m)
            }
        }
        if (!systemDone) {
            normalized.put(0, JSONObject().apply { put("role", "system"); put("content", IDENTITY_PREFIX) })
        }
        openAiBody.put("model", bodyModel)
        openAiBody.put("messages", normalized)

        val req = Request.Builder().url(CHAT)
            .post(openAiBody.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                signHeaders().forEach { (k, v) -> header(k, v) }
                header("X-Authorization", "Bearer $accessToken")  // 不是 Authorization！
                header("X-Request-Id", UUID.randomUUID().toString())
                header("X-Request-Model", routeId)
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
}
