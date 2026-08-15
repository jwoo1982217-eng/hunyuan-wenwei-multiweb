package com.wenwei.deepseekproxy.proxy

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WebSessionTestResult(val success: Boolean, val message: String, val modelIds: List<String> = emptyList())

class ChatGptWebClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
) {
    fun testSession(session: SavedWebSession): WebSessionTestResult {
        if (session.authToken.isBlank()) return WebSessionTestResult(false, "缺少 accessToken，请重新保存登录会话")
        val request = Request.Builder()
            .url("https://chatgpt.com/backend-api/models?history_and_training_disabled=false")
            .header("Authorization", "Bearer ${session.authToken}")
            .header("Cookie", session.cookieHeader)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    WebSessionTestResult(false, "HTTP ${response.code}: ${safeError(body)}")
                } else {
                    val root = JSONObject(body)
                    val array = root.optJSONArray("models") ?: root.optJSONArray("data")
                    val ids = buildList {
                        if (array != null) for (i in 0 until array.length()) {
                            val model = array.optJSONObject(i) ?: continue
                            val id = model.optString("slug", model.optString("id"))
                            if (id.isNotBlank()) add(id)
                        }
                    }.distinct()
                    WebSessionTestResult(true, "登录有效，发现 ${ids.size} 个模型", ids)
                }
            }
        }.getOrElse { WebSessionTestResult(false, "网络异常: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun safeError(body: String): String = runCatching {
        val obj = JSONObject(body)
        obj.optString("detail", obj.optString("message", "请求失败"))
    }.getOrDefault("请求失败").take(160)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    }
}
