package com.wenwei.deepseekproxy.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * DeepSeek（chat.deepseek.com）网页端接口客户端。
 *
 * 与 Doubao/Kimi 客户端风格一致：纯 OkHttp，无 wasm/JS 引擎。
 * 聊天需先完成 PoW（见 [DeepSeekPow]），再把答案塞进 `x-ds-pow-response` 头。
 *
 * 鉴权：Authorization: Bearer <token>（网页登录后的 token，非 Cookie）。
 */
object DeepSeekClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 150s 无字节即判死流（正常生成持续出帧；识图呆滞时及时释放互斥锁）
        .readTimeout(150, TimeUnit.SECONDS)
        .build()

    private const val BASE = "https://chat.deepseek.com"
    const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Bearer token，由 ProxyServer 在每次请求前注入（来自 UI 设置的凭证）。 */
    var token: String = ""

    /** 额外请求头（x-hif-leim 设备指纹等），来自保存会话时的捕获。 */
    @Volatile
    var extraHeaders: Map<String, String> = emptyMap()

    private fun newRequestBuilder(path: String): Request.Builder {
        return Request.Builder().url(BASE + path)
            .header("authorization", token)
            .header("user-agent", UA)
            .header("origin", BASE)
            .header("referer", BASE + "/")
            // 与网页端对齐：版本过旧会被风控降级（识图被静默丢弃）
            .header("x-client-version", "2.4.0")
            .header("x-client-platform", "web")
            .header("x-client-locale", "zh_CN")
            .header("x-client-bundle-id", "com.deepseek.chat")
            .header("x-client-timezone-offset", "28800")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .header("accept", "*/*")
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val req = newRequestBuilder(path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("DeepSeek $path HTTP ${resp.code}: ${text.take(300)}")
            }
            return JSONObject(text)
        }
    }

    fun createSession(): String {
        val d = postJson("/api/v0/chat_session/create", JSONObject())
        android.util.Log.i("DSVision", "create_session 响应: " + d.toString().take(1000))
        return d.getJSONObject("data").getJSONObject("biz_data")
            .getJSONObject("chat_session").getString("id")
    }

    /** 删除已完成的 DeepSeek 会话（静默执行，不阻塞）。回复完毕后调用，避免会话堆积。 */
    fun deleteSession(sessionId: String) {
        if (sessionId.isBlank()) return
        try {
            postJson(
                "/api/v0/chat_session/delete",
                JSONObject().put("chat_session_id", sessionId)
            )
        } catch (_: Exception) {
            // 删除失败不影响主流程，静默忽略
        }
    }

    data class PowChallenge(
        val algorithm: String,
        val challenge: String,
        val salt: String,
        val signature: String,
        val difficulty: Int,
        val expireAt: Long,
        val targetPath: String
    )

    fun createPowChallenge(targetPath: String): PowChallenge {
        val d = postJson(
            "/api/v0/chat/create_pow_challenge",
            JSONObject().put("target_path", targetPath)
        )
        val c = d.getJSONObject("data").getJSONObject("biz_data").getJSONObject("challenge")
        return PowChallenge(
            c.getString("algorithm"),
            c.getString("challenge"),
            c.getString("salt"),
            c.getString("signature"),
            c.getInt("difficulty"),
            c.getLong("expire_at"),
            c.getString("target_path")
        )
    }

    fun buildPowHeader(ch: PowChallenge, answer: Long, targetPath: String): String {
        val payload = JSONObject().apply {
            put("algorithm", ch.algorithm)
            put("challenge", ch.challenge)
            put("salt", ch.salt)
            put("answer", answer)
            put("signature", ch.signature)
            put("target_path", targetPath)
        }
        return Base64.getEncoder().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * 上传图片（识图/VISION）：multipart 上传 → file_id（供 chatCompletion 的 ref_file_ids 引用）。
     * 流程：create_pow_challenge(/api/v0/file/upload_file) → DeepSeekPow.solve → 带 x-ds-pow-response 上传
     * → fetch_files 轮询确认 status=SUCCESS（网页端同款，PENDING 时 completion 不识别）。
     */
    suspend fun uploadImage(bytes: ByteArray, fileName: String = "image.png", variant: String = "base"): String {
        // 1. POW challenge（与 completion 同款）
        val challenge = createPowChallenge("/api/v0/file/upload_file")
        val answer = DeepSeekPow.solve(
            challenge.challenge, challenge.salt, challenge.expireAt, challenge.difficulty
        )
        val powHeader = buildPowHeader(challenge, answer, "/api/v0/file/upload_file")

        // 2. multipart 上传（字段名 file）
        val mime = when {
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".webp", true) -> "image/webp"
            fileName.endsWith(".gif", true) -> "image/gif"
            else -> "image/jpeg"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
            .build()
        val rb = newRequestBuilder("/api/v0/file/upload_file")
            .post(body)
            .header("x-ds-pow-response", powHeader)
            .header("x-file-size", bytes.size.toString())
        if (variant != "novisionhdr") {
            rb.header("x-model-type", "vision").header("x-thinking-enabled", "0")
        }
        val req = rb.header("accept", "*/*").build()
        val fileId = client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                android.util.Log.e("DSVision", "上传 HTTP ${resp.code}: ${text.take(200)}")
                throw RuntimeException("DeepSeek 上传图片 HTTP ${resp.code}: ${text.take(300)}")
            }
            android.util.Log.i("DSVision", "上传响应: " + text)
            runCatching {
                JSONObject(text).getJSONObject("data").getJSONObject("biz_data").getString("id")
            }.getOrNull()
                ?: throw RuntimeException("DeepSeek 上传图片无 file_id: ${text.take(300)}")
        }
        android.util.Log.i("DSVision", "上传成功 fileId=$fileId，开始 fetch_files 轮询")
        // 3. fetch_files 轮询确认 SUCCESS + audit_result=pass（网页端在审核通过后才提交 completion）
        for (attempt in 0 until 40) {
            val info = fetchFileStatus(fileId)
            android.util.Log.i("DSVision", "fetch_files attempt=$attempt state=$info")
            if (info == "SUCCESS_PASS") break
            if (info.startsWith("FAILED") || info.startsWith("ERROR")) {
                throw RuntimeException("DeepSeek 图片处理失败: $info")
            }
            kotlinx.coroutines.delay(500)
        }
        return fileId
    }

    /** 查询文件状态（GET /api/v0/file/fetch_files?file_ids=...），返回 STATUS_AUDIT。 */
    private fun fetchFileStatus(fileId: String): String {
        return runCatching {
            val req = newRequestBuilder("/api/v0/file/fetch_files?file_ids=$fileId")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@use ""
                android.util.Log.i("DSVision", "fetch_files 响应: " + text.take(600))
                runCatching {
                    val file = JSONObject(text).getJSONObject("data").getJSONObject("biz_data")
                        .getJSONArray("files").optJSONObject(0) ?: return@runCatching ""
                    val status = file.optString("status", "")
                    val audit = file.optString("audit_result", "")
                    when {
                        status == "SUCCESS" && audit == "pass" -> "SUCCESS_PASS"
                        status == "FAILED" || status == "ERROR" -> "$status:$audit"
                        else -> "$status:$audit"
                    }
                }.getOrDefault("")
            }
        }.getOrDefault("")
    }

    /**
     * 调用 /api/v0/chat/completion（流式 SSE）。返回的 Response 由调用方读取并关闭。
     * 若非 SSE（如鉴权失败），会读取并抛出异常。
     */
    fun chatCompletion(
        sessionId: String, prompt: String, powHeader: String, modelType: String? = "default",
        refFileIds: List<String> = emptyList(),
        parentMessageId: Int? = null,
        variant: String = "base",
    ): Response {
        // DSVAR: variant 调试开关（X-DS-Variant 头控制），排查识图 completion 呆滞
        val body = JSONObject().apply {
            put("chat_session_id", sessionId)
            if (variant != "noparent") put("parent_message_id", parentMessageId ?: JSONObject.NULL)
            when (variant) {
                "mtdefault" -> put("model_type", "default")   // 假设A: 识图也用 default
                "mtvision" -> put("model_type", "vision")
                else -> put("model_type", modelType ?: JSONObject.NULL)
            }
            put("prompt", prompt)
            put("ref_file_ids", JSONArray().apply { refFileIds.forEach { put(it) } })
            // 假设D: 识图开思考（think 变体）；nothink 显式关；其余默认关
            if (variant == "think" && refFileIds.isNotEmpty()) {
                put("thinking_enabled", true)
            } else if (variant != "nothink") {
                put("thinking_enabled", false)
            }
            put("search_enabled", false)
            put("action", JSONObject.NULL)
            put("preempt", false)
        }
        android.util.Log.i("DSVision", "completion body: " + body.toString())
        val rb = newRequestBuilder("/api/v0/chat/completion")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("x-ds-pow-response", powHeader)
        if (variant == "raw") {
            rb.header("accept", "application/json, text/plain, */*")
        } else {
            rb.header("accept", "text/event-stream")
        }
        android.util.Log.i("DSVision", "completion variant=$variant body=" + body.toString())
        val req = rb.build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string() ?: ""
            resp.close()
            throw RuntimeException("DeepSeek /chat/completion HTTP ${resp.code}: ${err.take(300)}")
        }
        val ct = resp.header("Content-Type") ?: ""
        if (!ct.contains("text/event-stream", ignoreCase = true)) {
            val bodyText = resp.body?.string() ?: ""
            resp.close()
            throw RuntimeException("DeepSeek /chat/completion 响应异常(content-type=$ct): ${bodyText.take(300)}")
        }
        return resp
    }

    /**
     * 解析 DeepSeek SSE，只回调「模型真正的回复文本」。
     *
     * 真实线格式（同一端点 /api/v0/chat/completion，与 deepseek-chat-toolkit 的 py 参考一致）：
     *   每一行 `data:` 是一个独立 JSON 对象：
     *     - 元数据事件 {"response":{... fragments:[{content:"开头几个字"}] ...}}：
     *         开头那几个字可能在此（兼容多种字段位置），拼接为前缀，在首个正文前补上；
     *     - 增量文本事件 {"v":"..."}：v 字段即本段回复文本 → 立即回调（流式）；
     *     - 遥测事件 {"p":"...","v":"FINISHED"}：带 p 字段，是遥测参数，其 v 不是正文，跳过；
     *     - 遥测数组 [{"p":...}] 是 JSONArray，JSONObject 解析抛异常 → 忽略；
     *     - "FINISHED" 不是合法 JSON → 忽略。
     *   若整段是 blob（含 '!' 分隔符、无 v 流），则在循环结束后走 blob 回退提取。
     *
     */
    suspend fun parseSse(src: okio.BufferedSource, onDelta: suspend (String) -> Unit) {
        var prefix = ""
        var prefixEmitted = false
        var frameCount = 0
        var lastLogAt = 0L
        var gotV = false
        val rawForBlob = mutableListOf<String>()
        suspend fun emitPrefix() {
            if (!prefixEmitted && prefix.isNotEmpty()) {
                onDelta(prefix)
                prefixEmitted = true
            }
        }

        while (true) {
            val line = src.readUtf8Line() ?: break
            frameCount++
            val nowMs = System.currentTimeMillis()
            if (frameCount <= 8 || nowMs - lastLogAt > 2000) {
                android.util.Log.d("DSVision", "SSE frame#$frameCount: " + line.take(120))
                lastLogAt = nowMs
            }
            val t = line.trimStart()
            if (!t.startsWith("data:")) continue
            val data = t.substring(5).trim()
            // 调试：打印原始 SSE data（识图回复结构排查用）
            if (data.isNotEmpty() && data != "FINISHED") {
                android.util.Log.d("DSVision", "SSE data: " + data.take(600))
            }
            if (data.isEmpty() || data.startsWith("[") || data == "FINISHED") continue
            try {
                val obj = JSONObject(data)
                val vVal = obj.opt("v")
                when {
                    // 带 p 的补丁帧：2.4.0 协议下正文就是 {"p":"response/fragments/-1/content","o":"APPEND","v":"..."}
                    // p 指向 fragments content 的 APPEND 是真正正文增量（实测 "PONG" 只发后缀 "ONG"，
                    // 说明首字符在骨架 fragments 里，前缀逻辑已兜住）→ 直接回调；
                    // 其余 p 帧（BATCH 遥测、status SET、FINISHED 等）跳过。
                    obj.has("p") -> {
                        val p = obj.optString("p", "")
                        if (p.endsWith("/content") && obj.optString("o", "") == "APPEND") {
                            val pv = obj.optString("v", "")
                            if (pv.isNotEmpty()) {
                                gotV = true
                                emitPrefix()
                                onDelta(pv)
                            }
                        }
                    }
                    // 顶层 {"response":{...}}（无 v）：取 fragments 前缀
                    obj.has("response") -> {
                        prefix += extractOpening(obj)
                    }
                    // v 是字符串：真正的增量正文
                    vVal is String -> {
                        gotV = true
                        val v = vVal
                        // 去重：首个正文已包含前缀则不重复补（避免双字），否则先补前缀
                        if (!prefixEmitted && prefix.isNotEmpty() && v.startsWith(prefix)) {
                            prefixEmitted = true
                        } else {
                            emitPrefix()
                        }
                        onDelta(v)
                    }
                    // v 是对象（如 {"response":{...}} 开头 metadata）：只取 fragments 前缀，不要整段 JSON
                    vVal is JSONObject && vVal.has("response") -> {
                        prefix += extractOpening(vVal)
                    }
                }
            } catch (_: Exception) {
                // 非 JSON（如 blob 格式含 '!' 分隔）→ 仅在未遇到 v 流时收集用于回退
                if (!gotV) rawForBlob.add(data)
            }
        }
        // 未遇到任何 v 流：尝试 blob（整块）格式回退
        if (!gotV) {
            val blob = extractBlobText(rawForBlob.joinToString("\n"))
            if (blob.isNotEmpty()) {
                android.util.Log.i("DSVision", "blob 回退命中，共 $frameCount 帧")
                onDelta(blob)
            } else {
                android.util.Log.w("DSVision", "SSE 结束无内容：共 $frameCount 帧，gotV=false（服务器未发正文）")
            }
        }
    }

    /**
     * 从 {"response":{...}} 的 fragments 提取开头片段文本。
     * 只取 fragments[].content / fragments[].text（开头的字/几个字），
     * 不取 response.content 等整段字段，避免把前面一整段都显示出来。
     * 没有就返回空，按 v 流正常处理（无前缀）。
     */
    private fun extractOpening(respObj: JSONObject): String {
        return try {
            val resp = respObj.optJSONObject("response") ?: return ""
            val frags = resp.optJSONArray("fragments") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until frags.length()) {
                val f = frags.optJSONObject(i) ?: continue
                val c = f.optString("content", "")
                sb.append(if (c.isNotEmpty()) c else f.optString("text", ""))
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * blob（整块）格式回退：用户要求「只取 content 字段」。
     * 从 JSON metadata 提取主 content（排除 fragments 内部那几个开头字），
     * 不再返回 '!' 之后整段 body，避免把前面所有内容都返回。
     */
    private fun extractBlobText(raw: String): String {
        return extractPrimaryContent(raw).trimEnd()
    }

    /**
     * 提取主 content 字段（response.content / 顶层 content），排除 fragments 块内的 content。
     */
    private fun extractPrimaryContent(raw: String): String {
        return try {
            // 先把 fragments 数组清空，避免只匹配到 fragments 里那几个开头字
            val noFrag = Regex("\"fragments\"\\s*:\\s*\\[.*?\\]", RegexOption.DOT_MATCHES_ALL)
                .replace(raw, "\"fragments\":[]")
            val m = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(noFrag)
            if (m != null) return unescapeJson(m.groupValues[1])
            ""
        } catch (_: Exception) {
            ""
        }
    }

    /** 还原 JSON 字符串转义。 */
    private fun unescapeJson(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
