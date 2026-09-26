package com.wenwei.deepseekproxy.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 智谱清言（chatglm.cn）登录版接口客户端 —— 与 ZhipuClient（免登录游客通道）完全独立。
 *
 * 协议逆向自 GLMKit（github.com/yuzchu/GLMKit）的 GlmBackend.java：
 *   - 对话：POST https://chatglm.cn/chatglm/backend-api/assistant/stream （SSE）
 *   - 鉴权：Authorization: Bearer <authToken>（网页登录后的 JWT）+ Cookie 兜底
 *   - 请求体：assistant_id + messages[{role, content:[{type:text, text}]}] + meta_data.chat_mode
 *   - chat_mode: "" = 快速 / "zero" = 深度思考 / "deep_research" = 深度研究
 *   - SSE 增量：parts[logic_id].content[] 内容为累积值，需按 logic_id 记录已发长度取差量
 *
 * 仅使用用户自己在本机 WebView 登录后保存的凭据，不含任何共享或硬编码凭证。
 */
object ZhipuAuthedClient {
    private const val BASE = "https://chatglm.cn/chatglm"
    private const val STREAM_PATH = "/backend-api/assistant/stream"
    private const val DEFAULT_ASSISTANT_ID = "65940acff94777010aa6b796"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长流，不限制读超时
        .build()

    /** 从保存的凭据串中提取 JWT token（支持裸 token 或 Cookie/localStorage 混合串）。 */
    fun extractToken(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("eyJ")) return trimmed
        for (p in trimmed.split(';', '\n')) {
            val v = p.trim().substringAfter('=', "").trim()
                .removePrefix("Bearer ").removeSurrounding("\"")
            if (v.startsWith("eyJ")) return v
        }
        val names = setOf("token", "access_token", "auth_token", "chat_token", "authorization")
        for (p in trimmed.split(';', '\n')) {
            val idx = p.indexOf('=')
            if (idx < 0) continue
            val k = p.substring(0, idx).trim().lowercase()
            val v = p.substring(idx + 1).trim().removePrefix("Bearer ").removeSurrounding("\"")
            if (k in names && v.isNotEmpty()) return v
        }
        return trimmed
    }

    /** OpenAI messages → GLM 请求体（对齐 GLMKit buildGlmRequestBody）。 */
    private fun buildBody(messages: List<ChatMessage>, chatMode: String): String {
        val text = messages
            .filter { it.textContent().isNotBlank() }
            .joinToString("\n\n") { m ->
                val label = when (m.role) {
                    "system" -> "System"
                    "assistant" -> "Assistant"
                    else -> "User"
                }
                "$label: ${m.textContent()}"
            }

        val glmMessages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                    })
                })
            })
        }

        val metaData = JSONObject().apply {
            put("channel", "")
            put("chat_mode", chatMode)
            put("draft_id", "")
            put("if_plus_model", true)
            put("input_question_type", "xxxx")
            put("is_networking", false)
            put("is_test", false)
            put("platform", "pc")
            put("quote_log_id", "")
            put("cogview", JSONObject().put("rm_label_watermark", false))
        }

        return JSONObject().apply {
            put("assistant_id", DEFAULT_ASSISTANT_ID)
            put("conversation_id", "")
            put("project_id", "")
            put("chat_type", "user_chat")
            put("messages", glmMessages)
            put("meta_data", metaData)
        }.toString()
    }

    /**
     * 调用 GLM 流式接口，返回 SSE Response（调用方负责 close / use）。
     * token 必须来自用户自己的登录会话；cookie 作为兜底鉴权一并带上。
     */
    fun chatCompletion(
        messages: List<ChatMessage>,
        token: String,
        cookie: String,
        chatMode: String,
    ): Response {
        val auth = extractToken(token).removePrefix("Bearer ").trim()
        require(auth.isNotBlank()) { "清言授权为空，请先在浏览器登录智谱清言并保存会话" }
        val body = buildBody(messages, chatMode)
        val req = Request.Builder().url(BASE + STREAM_PATH)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $auth")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .header("Accept", "text/event-stream")
            .header("User-Agent", UA)
            .header("Origin", "https://chatglm.cn")
            .header("Referer", "https://chatglm.cn/")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string().orEmpty()
            resp.close()
            throw RuntimeException("GLM /assistant/stream HTTP ${resp.code}: ${err.take(300)}")
        }
        return resp
    }

    /**
     * 解析 GLM SSE 流。parts[logic_id].content[] 的 text 是**累积值**，
     * 按 logic_id 记录已发长度，每次只发增量。think 内容不混入正文。
     */
    suspend fun decodeSse(
        src: okio.BufferedSource,
        onText: suspend (String) -> Unit,
    ) {
        val sentLen = HashMap<String, Int>()
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break
            try {
                val event = JSONObject(data)
                val parts = event.optJSONArray("parts") ?: continue
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val logicId = part.optString("logic_id", "")
                    if (logicId.isEmpty()) continue
                    val contentItems = part.optJSONArray("content") ?: continue
                    val sb = StringBuilder()
                    for (j in 0 until contentItems.length()) {
                        val c = contentItems.optJSONObject(j) ?: continue
                        when (c.optString("type", "")) {
                            "text" -> sb.append(c.optString("text", ""))
                            "code" -> sb.append("```python\n").append(c.optString("code", "")).append("\n```")
                            "execution_output" -> sb.append(c.optString("content", ""))
                        }
                    }
                    val full = sb.toString()
                    val prev = sentLen[logicId] ?: 0
                    if (full.length > prev) {
                        onText(full.substring(prev))
                        sentLen[logicId] = full.length
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
