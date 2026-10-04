package com.wenwei.deepseekproxy.proxy

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 百炼（阿里云百炼模型体验页）匿名对话客户端。
 *
 * 协议移植自 Z·阅读 3.26（同源混元底子的兄弟项目，BailianClient 反汇编逐条核对）：
 *  1. 前置凭证（浏览器抓包三件套）：cookies + bx-ua + bx_et（bx-umidtoken 可选），
 *     由浏览器页的 shouldInterceptRequest 从 bailian-cs.console.aliyun.com
 *     的请求头里自动捕获（体验页发一条消息即触发）。
 *  2. RPC 网关：POST https://bailian-cs.console.aliyun.com/data/api.json
 *     ?action=BroadScopeAspnGateway&product=sfm_bailian&api=<Api>&_v=
 *     body = params=<URLEncode(JSON{Api,V:"1.0",Data})>，form-urlencoded
 *  3. createSession：Api = zeldaEasy.bmp.agentSessionRpcService.createApi
 *     Data.session = {bizId: modelId, bizType: "AgentInference", label: 随机50}
 *     → 响应 Data.data.sessionId
 *  4. generateAccessToken：Api = zeldaEasy.cornerstoneStreamGateway.
 *     streamGatewayConsoleService.generateAccessToken，Data.accessTokenRequest={source:""}
 *     → 响应 Data.data.accessToken
 *  5. 对话：POST https://bailian-stream-api.aliyuncs.com/sse/console4Json/<accessToken>
 *     body = {messageId, data:[{type:"JSON_TEXT", value:predictInner(...)}]}
 *     predictInner = JSON{Api: zeldaEasy.bmp.agentPredictRpcService.predict, V:"1.0",
 *       Data:{predictRequest:{modelId, ancestorModelId:"", modelTypeIds:[Reasoning,VU,TG],
 *       contentList:[{type:"text",content:prompt}], predictConfig:{modelParam:{
 *       enable_thinking:false, thinking_budget:4000}, chatType:"t2t"}, reGenerate:false,
 *       chatLogCode, sessionId, isAiCenterRequest:false, tabCode}, cornerstoneParam}}
 *  6. cornerstoneParam（每个请求都带）：{feTraceId, feURL, protocol:"V2",
 *     console:"ONE_CONSOLE", productCode:"p_efm", switchUserType:3,
 *     domain:"bailian.console.aliyun.com", consoleSite:"BAILIAN_ALIYUN", xsp_lang:"zh-CN"}
 *
 * 额度绑浏览器会话（Cookie）——「清空」Cookie 再访问即得新游客额度。
 */
object BailianClient {
    private const val TAG = "Bailian"
    private const val CS_BASE = "https://bailian-cs.console.aliyun.com/data/api.json"
    private const val STREAM_BASE = "https://bailian-stream-api.aliyuncs.com/sse/console4Json/"
    private const val FE_URL = "https://bailian.console.aliyun.com/cn-beijing/model/experience/llm"
    const val DOMAIN = "bailian.console.aliyun.com"

    @Volatile var cookies: String = ""
    @Volatile var bxUa: String = ""
    @Volatile var bxEt: String = ""
    @Volatile var bxUmidToken: String = ""
    @Volatile var modelId: String = "qwen3.8-max"
    @Volatile var userAgent: String =
        "Mozilla/5.0 (Linux; Android 15; V2352A Build/AP3A.240905.015.A2) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.6478.71 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun applyConfig(json: String) {
        if (json.isBlank()) {
            cookies = ""; bxUa = ""; bxEt = ""; bxUmidToken = ""; modelId = "qwen3.8-max"
            return
        }
        runCatching {
            val o = JSONObject(json)
            cookies = o.optString("cookies", "")
            bxUa = o.optString("bx_ua", "")
            bxEt = o.optString("bx_et", "")
            bxUmidToken = o.optString("bx_umidtoken", "")
            o.optString("model_id", "").takeIf { it.isNotBlank() }?.let { modelId = it }
            o.optString("user_agent", "").takeIf { it.isNotBlank() }?.let { userAgent = it }
        }
    }

    // ── 凭证池（对齐 Z·阅读：多套游客会话，每次请求轮换，额度均摊）──
    private val poolLock = Any()
    private val configPool = mutableListOf<String>()
    private val rotationIdx = java.util.concurrent.atomic.AtomicInteger(0)

    /** 设置凭证池（UI 侧持久化为准，这里只挂运行时） */
    fun setConfigList(list: List<String>) {
        synchronized(poolLock) {
            configPool.clear()
            configPool.addAll(list.filter { it.isNotBlank() })
        }
        LogStore.i("Bailian", "凭证池已挂载 ${list.size} 套")
    }

    /**
     * 轮换到下一套凭证（(idx+1) % size，与 Z·阅读 applyNextBailianConfig 一致）。
     * 池子为空时不动当前配置（兼容旧的单套 bailian_config）。
     */
    fun applyNext() {
        val list = synchronized(poolLock) { configPool.toList() }
        if (list.isEmpty()) return
        val idx = rotationIdx.getAndUpdate { i -> (i + 1) % list.size }
        val next = list.getOrNull(idx % list.size) ?: return
        applyConfig(next)
    }

    fun isConfigured(): Boolean =
        cookies.isNotBlank() && bxUa.isNotBlank() && bxEt.isNotBlank()

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun cornerstone(): JSONObject = JSONObject().apply {
        put("feTraceId", uuid())
        put("feURL", FE_URL)
        put("protocol", "V2")
        put("console", "ONE_CONSOLE")
        put("productCode", "p_efm")
        put("switchUserType", 3)
        put("domain", DOMAIN)
        put("consoleSite", "BAILIAN_ALIYUN")
        put("xsp_lang", "zh-CN")
    }

    private fun rpcHeaders(): Headers = Headers.Builder().apply {
        add("User-Agent", userAgent)
        add("Content-Type", "application/x-www-form-urlencoded")
        add("Accept", "*/*")
        add("Origin", "https://$DOMAIN")
        add("Referer", FE_URL)
        add("X-Requested-With", "XMLHttpRequest")
        if (bxUa.isNotBlank()) add("bx-ua", bxUa)
        if (bxEt.isNotBlank()) add("bx_et", bxEt)
        if (bxUmidToken.isNotBlank()) add("bx-umidtoken", bxUmidToken)
        if (cookies.isNotBlank()) add("Cookie", cookies)
    }.build()

    private fun rpc(api: String, dataJson: JSONObject): JSONObject {
        val envelope = JSONObject().apply {
            put("Api", api)
            put("V", "1.0")
            put("Data", dataJson)
        }
        val url = CS_BASE + "?action=BroadScopeAspnGateway&product=sfm_bailian&api=" +
            URLEncoder.encode(api, "UTF-8") + "&_v=" + URLEncoder.encode(
            (System.currentTimeMillis() / 1000).toString(), "UTF-8")
        val body = "params=" + URLEncoder.encode(envelope.toString(), "UTF-8")
        val req = Request.Builder().url(url)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .apply { rpcHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw RuntimeException("百炼 RPC 失败 HTTP ${r.code}: ${text.take(200)}")
            }
            val j = runCatching { JSONObject(text) }.getOrElse {
                throw RuntimeException("百炼 RPC 响应非 JSON: ${text.take(200)}")
            }
            if (j.optString("code") != "200") {
                throw RuntimeException("百炼 RPC 失败 code=" + j.optString("code") + ": " + j.optString("message"))
            }
            // 响应结构（实测）：{code, data: {DataV2: {data: {codeEnum, success, data: 记录层}, ret}}}
            // rpc 返回记录层（sessionId / accessToken 都在这层）
            val layer = j.optJSONObject("data")?.optJSONObject("DataV2")?.optJSONObject("data")
            val success = layer?.optBoolean("success", false) ?: false
            if (!success) {
                throw RuntimeException(
                    "百炼 RPC 失败: " + (layer?.optString("codeEnum") ?: text.take(120)))
            }
            return layer?.optJSONObject("data") ?: JSONObject()
        }
    }

    private fun generateAccessToken(): String {
        val data = JSONObject().apply {
            put("accessTokenRequest", JSONObject().apply { put("source", "") })
            put("cornerstoneParam", cornerstone())
        }
        val resp = rpc("zeldaEasy.cornerstoneStreamGateway.streamGatewayConsoleService.generateAccessToken", data)
        val token = resp.optString("accessToken", "")
        if (token.isBlank()) throw RuntimeException("百炼未返回 accessToken")
        return token
    }

    private fun createSession(prompt: String): String {
        val data = JSONObject().apply {
            put("session", JSONObject().apply {
                put("bizId", modelId)
                put("bizType", "AgentInference")
                put("label", randomLabel())
            })
            put("cornerstoneParam", cornerstone())
        }
        val resp = rpc("zeldaEasy.bmp.agentSessionRpcService.createApi", data)
        val sid = resp.optString("sessionId", "")
        if (sid.isBlank()) throw RuntimeException("百炼未返回 sessionId")
        return sid
    }

    private fun randomLabel(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..50).map { chars.random() }.joinToString("")
    }

    private fun predictInner(prompt: String, sessionId: String, tabCode: String): String {
        val envelope = JSONObject().apply {
            put("Api", "zeldaEasy.bmp.agentPredictRpcService.predict")
            put("V", "1.0")
            put("Data", JSONObject().apply {
                put("predictRequest", JSONObject().apply {
                    put("modelId", modelId)
                    put("ancestorModelId", "")
                    put("modelTypeIds", JSONArray().put("Reasoning").put("VU").put("TG"))
                    put("contentList", JSONArray().put(JSONObject().apply {
                        put("type", "text")
                        put("content", prompt)
                    }))
                    put("predictConfig", JSONObject().apply {
                        put("modelParam", JSONObject().apply {
                            put("enable_thinking", false)
                            put("thinking_budget", 4000)
                        })
                        put("chatType", "t2t")
                    })
                    put("reGenerate", false)
                    put("chatLogCode", uuid())
                    put("sessionId", sessionId)
                    put("isAiCenterRequest", false)
                    put("tabCode", tabCode)
                })
                put("cornerstoneParam", cornerstone())
            })
        }
        return envelope.toString()
    }

    /** 递归提取 Bailian SSE JSON 里的文本（messageList → contentList → text 及通用兜底） */
    fun collectTexts(any: Any?, out: MutableList<String>) {
        when (any) {
            is JSONObject -> {
                // 优先走已知结构
                val ml = any.optJSONArray("messageList")
                if (ml != null) {
                    for (i in 0 until ml.length()) collectTexts(ml.opt(i), out)
                    if (ml.length() > 0) return
                }
                val cl = any.optJSONArray("contentList")
                if (cl != null) {
                    for (i in 0 until cl.length()) collectTexts(cl.opt(i), out)
                    if (cl.length() > 0) return
                }
                val t = any.optJSONArray("text")
                if (t != null) {
                    for (i in 0 until t.length()) collectTexts(t.opt(i), out)
                    if (t.length() > 0) return
                }
                val keys = any.keys()
                while (keys.hasNext()) collectTexts(any.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until any.length()) collectTexts(any.opt(i), out)
            is String -> if (any.isNotEmpty()) out.add(any)
        }
    }

    /**
     * 发起百炼对话，返回 SSE 响应（调用方负责关闭与解析）。
     * messages: [{role, content}]（content 兼容 string / 数组）
     */
    fun chatCompletion(messages: JSONArray, model: String): Response {
        if (!isConfigured()) {
            throw IllegalStateException(
                "百炼未配置：请到浏览器打开百炼体验页并发送一条消息，自动捕获凭证（cookies + bx 三件套）")
        }
        if (model.isNotBlank()) modelId = model
        // 拼平铺 prompt（与 Z·阅读 一致：role: content 换行连接）
        val parts = mutableListOf<String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user").lowercase()
            val content = when {
                m.optString("content").isNotEmpty() -> m.optString("content")
                else -> {
                    val arr = m.optJSONArray("content") ?: continue
                    (0 until arr.length()).mapNotNull { a ->
                        val item = arr.optJSONObject(a) ?: return@mapNotNull null
                        if (item.optString("type") == "text") item.optString("text") else null
                    }.joinToString("\n")
                }
            }
            if (content.isBlank()) continue
            parts.add("$role: $content")
        }
        val prompt = parts.joinToString("\n\n")

        val sessionId = createSession(prompt)
        val accessToken = generateAccessToken()

        val payload = JSONObject().apply {
            put("messageId", uuid())
            put("data", JSONArray().put(JSONObject().apply {
                put("type", "JSON_TEXT")
                put("value", predictInner(prompt, sessionId, uuid().replace("-", "")))
            }))
        }
        val req = Request.Builder()
            .url(STREAM_BASE + accessToken)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Origin", "https://$DOMAIN")
            .header("Referer", FE_URL)
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val text = resp.body?.string().orEmpty()
            val code = resp.code
            resp.close()
            throw RuntimeException("百炼对话失败 HTTP $code: ${text.take(300)}")
        }
        peekBusinessError(resp)?.let { err ->
            resp.close()
            throw RuntimeException(err)
        }
        return resp
    }

    private fun peekBusinessError(resp: Response): String? {
        val body = runCatching { resp.peekBody(512).string().trim() }.getOrNull() ?: return null
        if (!body.startsWith("{")) return null
        val j = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (j.optInt("code", 0) == 0 && j.optString("codeEnum", "SUCCESS") == "SUCCESS") return null
        return "百炼对话失败: " + j.optString("message", body.take(200))
    }

    /** 解析百炼 SSE 流（实测结构）：信封 {data:[{type:"JSON_TEXT",value:"<JSON字符串>"}]} →
     *  内层 {codeEnum, data:{messageList:[{contentList:[{content}]}]}}；
     *  messageList 为累积快照，按前缀差量发射 */
    suspend fun parseBailianSse(source: okio.BufferedSource, onText: suspend (String) -> Unit) {
        var emitted = ""
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val l = line.trim()
            if (!l.startsWith("data:")) continue
            val payload = l.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val env = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            val arr = env.optJSONArray("data") ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("type") != "JSON_TEXT") continue
                val inner = runCatching { JSONObject(item.optString("value")) }.getOrNull() ?: continue
                if (inner.optString("code", "200") != "200" &&
                    inner.optString("codeEnum", "SUCCESS") != "SUCCESS"
                ) {
                    throw RuntimeException("百炼对话失败: " + inner.optString("message", "code=" + inner.optString("code")))
                }
                val ml = inner.optJSONObject("data")?.optJSONArray("messageList") ?: continue
                val sb = StringBuilder()
                for (m in 0 until ml.length()) {
                    val cl = ml.optJSONObject(m)?.optJSONArray("contentList") ?: continue
                    for (c in 0 until cl.length()) {
                        val co = cl.optJSONObject(c) ?: continue
                        // contentList[0] 是格式标记（content="Text"，labels 含 response_format），跳过
                        val labels = co.optJSONArray("labels")
                        var isMarker = false
                        if (labels != null) {
                            for (k in 0 until labels.length()) {
                                if (labels.optString(k) == "response_format") { isMarker = true; break }
                            }
                        }
                        if (!isMarker) sb.append(co.optString("content", ""))
                    }
                }
                val text = sb.toString()
                val delta = when {
                    text.length <= emitted.length -> ""            // 旧快照或空
                    text.startsWith(emitted) -> text.substring(emitted.length)  // 累积快照差量
                    else -> text                                    // 纯增量分片
                }
                if (delta.isNotEmpty()) {
                    emitted += delta
                    onText(delta)
                }
            }
        }
    }
}
