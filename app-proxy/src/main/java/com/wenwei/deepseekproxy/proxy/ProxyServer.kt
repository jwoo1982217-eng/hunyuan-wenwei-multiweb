package com.wenwei.deepseekproxy.proxy

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

@Serializable
data class ChatMessage(val role: String, val content: JsonElement = JsonPrimitive("")) {
    fun textContent(): String = when {
        content is JsonPrimitive -> content.content
        else -> runCatching {
            content.jsonArray.mapNotNull { part ->
                val obj = part.jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "text") {
                    obj["text"]?.jsonPrimitive?.content
                } else null
            }.joinToString("\n")
        }.getOrDefault("")
    }

    fun attachments(): List<ChatAttachment> = runCatching {
        content.jsonArray.mapNotNull { part ->
            val obj = part.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "image_url" -> obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content?.let { url ->
                    val mime = mimeFromDataUrl(url)
                    ChatAttachment("image.${mime.substringAfter('/').substringBefore(';').ifBlank { "png" }}", mime, url)
                }
                "file" -> obj["file"]?.jsonObject?.let { file ->
                    val data = file["file_data"]?.jsonPrimitive?.content ?: return@let null
                    ChatAttachment(file["filename"]?.jsonPrimitive?.content ?: "attachment", mimeFromDataUrl(data), data)
                }
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}

data class ChatAttachment(val name: String, val mime: String, val dataUrl: String)

private fun mimeFromDataUrl(value: String): String =
    value.substringAfter("data:", "application/octet-stream").substringBefore(';').ifBlank { "application/octet-stream" }

@Serializable
data class ChatCompletionRequest(
    val model: String = "hy3-preview",
    val messages: List<ChatMessage> = emptyList(),
    val stream: Boolean = false,
    val temperature: Double? = null,
    val max_tokens: Int? = null,
    val conversation_id: String? = null
)

@Serializable
data class ChoiceDelta(
    val role: String? = null,
    val content: String? = null,
    val images: List<String> = emptyList()
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChoiceDelta,
    val finish_reason: String? = null
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Int,
    val model: String,
    val choices: List<ChunkChoice>
)

@Serializable
data class Msg(val role: String, val content: String, val images: List<String> = emptyList())

@Serializable
data class Choice(
    val index: Int = 0,
    val message: Msg,
    val finish_reason: String? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

@Serializable
data class ChatCompletion(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Int,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
    val conversation_id: String
)

@Serializable
data class ImageDataItem(val url: String, val b64_json: String? = null)

@Serializable
data class ImageGenResponse(val created: Int, val data: List<ImageDataItem>)

@Serializable
data class ModelListItem(
    val id: String,
    val `object`: String = "model",
    val owned_by: String,
    val display_name: String,
    val capabilities: List<String>
)

@Serializable
data class ModelListResponse(
    val `object`: String = "list",
    val data: List<ModelListItem>
)

internal fun discoverableWebRelayModels(models: List<ProviderModel>): List<ProviderModel> =
    models.filter { it.enabled && it.providerType != WebProviderType.OPENAI_COMPATIBLE }

internal enum class CompatibilityProxyRequestKind {
    CHAT,
    IMAGE,
    UNKNOWN,
}

/**
 * Some generic clients persist the discovery URL itself as a CUSTOM endpoint and then POST
 * every invocation back to it. Identify the OpenAI request shape so those clients can still
 * use the gateway without weakening the canonical endpoint contracts.
 */
internal fun classifyCompatibilityProxyRequest(rawRequest: String): CompatibilityProxyRequestKind =
    runCatching {
        val body = Json.parseToJsonElement(rawRequest).jsonObject
        when {
            "messages" in body -> CompatibilityProxyRequestKind.CHAT
            "prompt" in body -> CompatibilityProxyRequestKind.IMAGE
            else -> CompatibilityProxyRequestKind.UNKNOWN
        }
    }.getOrDefault(CompatibilityProxyRequestKind.UNKNOWN)

private val RequestedModelKey = AttributeKey<String>("multiweb-requested-model")
private val RequestedStreamKey = AttributeKey<Boolean>("multiweb-requested-stream")

/**
 * 本地 OpenAI 兼容中转服务（端口默认 8800）。
 * 上游按模型名路由：
 *   - 豆包（Doubao）模型  → 豆包接口（聊天 / 生图）
 *   - 其他（含混元 hy3）  → CNB 混元3
 */
object ProxyServer {
    const val BIND_HOST = "127.0.0.1"
    private val json = Json { ignoreUnknownKeys = true }
    private var engine: EmbeddedServer<*, *>? = null
    @Volatile private var localBearerToken: String = ""
    @Volatile private var boundPort: Int = 0

    /** 是否向上游（CNB）使用流式请求。由 UI 开关实时控制。 */
    @Volatile var streamMode: Boolean = false

    /** 生图默认比例（豆包 ratio 串），由设置界面实时控制，默认 9:16 竖屏。 */
    @Volatile var defaultImageSize: String = "9:16"

    /** 豆包 Cookie 列表（用于请求时自动轮换），由 UI 注入。 */
    private val cookieLock = Any()
    @Volatile private var cookieRotationList: List<String> = emptyList()
    private var cookieRotationNames: List<String> = emptyList()
    private val cookieRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var webSessions: List<SavedWebSession> = emptyList()
    private val kimiRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)
    private val deepSeekRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)
    private val qwenRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)
    private val deepSeekRequestMutex = Mutex()

    fun setWebSessions(sessions: List<SavedWebSession>) {
        webSessions = sessions.toList()
    }

    private fun nextAuthorization(provider: WebProviderType, index: java.util.concurrent.atomic.AtomicInteger): String {
        val candidates = webSessions.filter { it.providerType == provider }.mapNotNull { session ->
            (session.authToken.ifBlank { session.cookieHeader }).takeIf(String::isNotBlank)
        }
        if (candidates.isEmpty()) return ""
        return candidates[index.getAndUpdate { (it + 1) % candidates.size } % candidates.size]
    }

    /** 设置参与轮换的 Cookie 列表（自动过滤空值，并保留对应名称）。 */
    fun setCookieList(list: List<String>) {
        synchronized(cookieLock) {
            val pairs = list.mapIndexed { i, c ->
                val name = if (i == 0) "默认Cookie" else "Cookie ${i + 1}"
                name to c
            }.filter { it.second.isNotBlank() }
            cookieRotationList = pairs.map { it.second }
            cookieRotationNames = pairs.map { it.first }
            if (cookieRotationIdx.get() >= cookieRotationList.size) cookieRotationIdx.set(0)
        }
    }

    /** 取下一个用于请求的 Cookie（在已保存的非空 Cookie 间轮询）。 */
    fun nextCookie(): String {
        val list = synchronized(cookieLock) { cookieRotationList }
        if (list.isEmpty()) {
            LogStore.i("Doubao", "轮换：暂无可用 Cookie（列表为空）")
            return ""
        }
        val idx = cookieRotationIdx.getAndUpdate { (it + 1) % list.size }
        val c = list[idx]
        val name = synchronized(cookieLock) { cookieRotationNames.getOrNull(idx) ?: "Cookie ${idx + 1}" }
        val preview = if (c.length <= 16) c else c.take(16) + "...(len=${c.length})"
        LogStore.i(
            "Doubao",
            "轮换：使用 [$name]（第 $idx 个，共 ${list.size} 个），预览=${preview}"
        )
        return c
    }

    /** 识别为豆包模型的名称（前缀匹配，大小写不敏感）。 */
    private val DOUBAO_MODELS = listOf("doubao", "豆包")

    fun isRunning() = engine != null

    fun setBearerToken(token: String) {
        require(token.isNotBlank()) { "Local gateway Bearer token must not be blank" }
        localBearerToken = token
    }

    fun start(
        port: Int,
        csrfkeyProvider: () -> String,
        cookieListProvider: () -> List<String>,
        storageDir: File,
        bearerToken: String,
    ) {
        stop()
        GatewayModelHealth.clear()
        setBearerToken(bearerToken)
        boundPort = port
        CnbClient.csrfkeySeed = csrfkeyProvider()
        CnbClient.storageDir = storageDir
        GeneratedImageStore.configure(storageDir, port)
        setCookieList(cookieListProvider())
        // 启动服务时强制刷新一次混元凭证（APP 重新打开 / 服务（重）启动场景）
        thread(name = "cnb-auth-init") {
            runCatching { CnbClient.ensureAuth(force = true) }
        }
        engine = embeddedServer(CIO, port = port, host = BIND_HOST) {
            install(ContentNegotiation) { json(json) }
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
            }
            routing {
                get("/") {
                    call.respond(
                        mapOf(
                            "service" to "Hunyuan/Doubao Proxy (OpenAI-compatible)",
                            "bind_host" to BIND_HOST,
                            "auth_required" to true,
                            "endpoint" to "POST /v1/chat/completions",
                            "image_endpoint" to "POST /v1/images/generations"
                        )
                    )
                }
                get("/v1/status") {
                    if (!call.requireLocalGatewayAuth()) return@get
                    call.respond(currentStatus())
                }
                get("/v1/models") {
                    if (!call.requireLocalGatewayAuth()) return@get
                    // Model discovery must not depend on an invocation that can only
                    // happen after the client has discovered the model id. This local
                    // endpoint is specifically the web-to-API catalog; ordinary
                    // OpenAI-compatible upstreams configured on the "接口" screen are
                    // intentionally excluded to keep client model lists uncluttered.
                    // Detailed runtime readiness remains available from /v1/status.
                    call.respond(ModelListResponse(data = discoverableWebRelayModels(
                        ProviderCatalog.enabledModels
                    )
                        .map { model ->
                        ModelListItem(
                            id = model.publicModelId,
                            owned_by = model.providerType.name.lowercase(),
                            display_name = model.displayName,
                            capabilities = model.reportedCapabilities.map { it.name }
                        )
                    }))
                }
                post("/v1/chat/completions") {
                    if (!call.requireLocalGatewayAuth()) return@post
                    handleChatCompletions()
                    call.recordRequestedModelHealth()
                }
                post("/v1/images/generations") {
                    if (!call.requireLocalGatewayAuth()) return@post
                    handleImageGen()
                    call.recordRequestedModelHealth()
                }
                // 墨听等通用客户端可能把 GET /v1/models 的发现地址保存成 CUSTOM
                // 调用地址，随后把聊天和生图请求直接 POST 回该地址。按请求体形状
                // 自动分流，现有配置无需删除重建。
                post("/v1/models") {
                    if (!call.requireLocalGatewayAuth()) return@post
                    handleCompatibilityProxyPost()
                    call.recordRequestedModelHealth()
                }
                post("/models") {
                    if (!call.requireLocalGatewayAuth()) return@post
                    handleCompatibilityProxyPost()
                    call.recordRequestedModelHealth()
                }
                post("/v1") {
                    if (!call.requireLocalGatewayAuth()) return@post
                    handleCompatibilityProxyPost()
                    call.recordRequestedModelHealth()
                }
                // Some local image clients store only http://127.0.0.1:PORT and
                // POST directly to that value instead of appending the OpenAI path.
                // Keep the canonical endpoint above while accepting both legacy forms.
                post("/images/generations") {
                    if (!call.requireLocalGatewayAuth()) return@post
                    handleImageGen()
                    call.recordRequestedModelHealth()
                }
                post("/") {
                    if (!call.requireLocalGatewayAuth()) return@post
                    handleImageGen()
                    call.recordRequestedModelHealth()
                }
                get("/v1/generated-images/{id}") {
                    // The random UUID filename is a capability URL returned to image
                    // clients. Many OpenAI-compatible clients do not forward the API
                    // Authorization header when downloading the resulting image.
                    val id = call.parameters["id"].orEmpty()
                    val stored = withContext(Dispatchers.IO) { GeneratedImageStore.read(id) }
                    if (stored == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "generated_image_not_found"))
                    } else {
                        call.respondBytes(stored.first, ContentType.parse(stored.second))
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
        boundPort = 0
    }

    private suspend fun ApplicationCall.requireLocalGatewayAuth(): Boolean {
        if (LocalGatewaySecurity.accepts(request.headers[HttpHeaders.Authorization], localBearerToken)) {
            return true
        }
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to mapOf(
                "type" to "authentication_error",
                "code" to "invalid_bearer_token",
                "message" to "Missing or invalid local gateway Bearer token",
            )),
        )
        return false
    }

    private fun ApplicationCall.recordRequestedModelHealth() {
        val model = attributes.getOrNull(RequestedModelKey) ?: return
        val code = response.status()?.value ?: return
        when {
            code in 200..299 && attributes.getOrNull(RequestedStreamKey) != true -> {
                GatewayModelHealth.record(model, true)
            }
            code >= 401 -> GatewayModelHealth.record(model, false)
        }
    }

    private fun currentStatus(): LocalGatewayStatusResponse {
        val models = ProviderCatalog.enabledModels
        val authenticated = webSessions.mapNotNull { session ->
            val ready = when (session.providerType) {
                WebProviderType.CHATGPT_WEB -> session.authToken.isNotBlank() || session.cookieHeader.isNotBlank()
                WebProviderType.GEMINI_WEB -> session.cookieHeadersByOrigin.values.any(String::isNotBlank)
                WebProviderType.KIMI_WEB,
                WebProviderType.DEEPSEEK_WEB -> session.authToken.isNotBlank()
                WebProviderType.QWEN_WEB -> session.authToken.isNotBlank() || session.cookieHeader.isNotBlank()
                else -> session.authToken.isNotBlank() || session.cookieHeader.isNotBlank()
            }
            session.providerType.takeIf { ready }
        }.toSet()
        val cookies = synchronized(cookieLock) { cookieRotationList.toList() }
        // ApiRouteSelector also contains GET /models discovery results. Discovery proves
        // connectivity only; public model readiness is promoted exclusively by a real
        // chat/image invocation recorded in GatewayModelHealth.
        val health = GatewayModelHealth.snapshot(models.map(ProviderModel::publicModelId))
        val attachedChat = when {
            ChatGptWebChatAutomation.isAttached() -> WebProviderType.CHATGPT_WEB.name
            GeminiWebChatAutomation.isAttached() -> WebProviderType.GEMINI_WEB.name
            else -> CustomWebChatAutomation.attachedConfigId()
        }
        val attachedImage = when {
            ChatGptWebAutomation.isAttached() -> WebProviderType.CHATGPT_WEB.name
            GeminiWebImageAutomation.isAttached() -> WebProviderType.GEMINI_WEB.name
            RaphaelWebImageAutomation.isAttached() -> WebProviderType.RAPHAEL_WEB.name
            else -> CustomWebImageAutomation.attachedConfigId()
        }
        return LocalGatewayStatusResolver.build(
            models = models,
            snapshot = GatewayRuntimeSnapshot(
                cnbAuthenticated = CnbClient.isAuthenticated(),
                doubaoLoggedIn = cookies.any(WebCookieSnapshot::hasDoubaoLoginCookie),
                authenticatedSessionProviders = authenticated,
                attachedChatProviderId = attachedChat,
                attachedImageProviderId = attachedImage,
                modelHealth = health,
            ),
            bindHost = BIND_HOST,
            port = boundPort,
        )
    }

    private fun mapModel(m: String): String {
        return when (m) {
            "hy3-preview", "glm-5.2", "deepseek-v4-pro", "deepseek-v4-flash" -> m
            else -> CnbClient.DEFAULT_MODEL
        }
    }

    private suspend fun RoutingContext.handleCompatibilityProxyPost() {
        val rawRequest = call.receiveText()
        when (classifyCompatibilityProxyRequest(rawRequest)) {
            CompatibilityProxyRequestKind.CHAT -> handleChatCompletions(rawRequest)
            CompatibilityProxyRequestKind.IMAGE -> handleImageGen(rawRequest)
            CompatibilityProxyRequestKind.UNKNOWN -> call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to mapOf(
                    "type" to "invalid_request_error",
                    "code" to "unknown_request_shape",
                    "message" to "Expected an OpenAI chat request with messages or an image request with prompt",
                )),
            )
        }
    }

    private suspend fun RoutingContext.handleChatCompletions(rawRequestOverride: String? = null) {
        try {
            val rawRequest = rawRequestOverride ?: call.receiveText()
            val req = json.decodeFromString<ChatCompletionRequest>(rawRequest)
            call.attributes.put(RequestedModelKey, req.model)
            call.attributes.put(RequestedStreamKey, req.stream)
            LogStore.i(
                "Proxy",
                "收到请求 | clientStream=${req.stream} upstreamStream=$streamMode model=${req.model} msgs=${req.messages.size}"
            )

            val registeredModel = ProviderCatalog.find(req.model)
            if (registeredModel == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to mapOf("type" to "unknown_model", "message" to "Unknown model: ${req.model}"))
                )
                return
            }

            if (registeredModel.providerType == WebProviderType.OPENAI_COMPATIBLE) {
                handleOpenAiCompatibleChat(registeredModel, req, rawRequest)
                return
            }

            // 豆包模型 → 走豆包接口（聊天）
            if (registeredModel.providerType == WebProviderType.DOUBAO_WEB) {
                val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
                val created = (System.currentTimeMillis() / 1000).toInt()
                handleDoubaoChat(req, completionId, created)
                return
            }

            if (registeredModel.providerType == WebProviderType.CHATGPT_WEB) {
                handleChatGptWebChat(req)
                return
            }

            if (registeredModel.providerType == WebProviderType.GEMINI_WEB) {
                handleGeminiWebChat(req)
                return
            }

            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            if (registeredModel.providerType == WebProviderType.KIMI_WEB) {
                handleKimiChat(req, completionId, created, WebProviderType.KIMI_WEB)
                return
            }
            if (registeredModel.providerType == WebProviderType.DEEPSEEK_WEB) {
                handleDeepSeekChat(req, completionId, created)
                return
            }
            if (registeredModel.providerType == WebProviderType.QWEN_WEB) {
                handleQwenChat(registeredModel, req, completionId, created)
                return
            }
            if (registeredModel.providerType == WebProviderType.ZHIPU_GUEST) {
                handleZhipuChat(registeredModel, req, completionId, created)
                return
            }
            if (isBuiltInBrowserRelay(registeredModel.providerType)) {
                handleBuiltInBrowserChat(registeredModel, req, completionId, created)
                return
            }
            if (registeredModel.providerType == WebProviderType.CUSTOM_WEB) {
                handleCustomWebChat(registeredModel, req, completionId, created)
                return
            }

            val model = mapModel(registeredModel.upstreamModelId)
            val bodyJson = org.json.JSONObject().apply {
                put("model", model)
                // CNB's current HY3 endpoint rejects non-stream requests
                // (code 11101). Always stream from CNB; handleStreamUpstream()
                // already converts the SSE into an ordinary completion when the
                // caller requested stream=false.
                put("stream", true)
                put(
                    "messages",
                    org.json.JSONArray(
                        req.messages.map {
                            org.json.JSONObject().put("role", it.role).put("content", org.json.JSONTokener(it.content.toString()).nextValue())
                        }
                    )
                )
                req.temperature?.let { put("temperature", it) }
                req.max_tokens?.let { put("max_tokens", it) }
            }

            handleStreamUpstream(req, model, bodyJson.toString(), completionId, created)
        } catch (e: Exception) {
            LogStore.e("Proxy", "请求处理失败: ${e.stackTraceToString()}")
            if (!call.response.isSent) {
                try {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.localizedMessage ?: e.toString()))
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun RoutingContext.respondProviderText(
        req: ChatCompletionRequest,
        completionId: String,
        created: Int,
        producer: suspend (suspend (String) -> Unit) -> Unit,
    ) {
        if (!req.stream) {
            val content = try {
                withTimeout(300_000) {
                    buildString { producer { append(it) } }
                }.also { GatewayModelHealth.record(req.model, true) }
            } catch (e: Exception) {
                GatewayModelHealth.record(req.model, false)
                throw e
            }
            call.respond(
                ChatCompletion(
                    completionId, "chat.completion", created, req.model,
                    listOf(Choice(message = Msg("assistant", content), finish_reason = "stop")),
                    Usage(), ""
                )
            )
            return
        }
        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Text.EventStream
            override val headers = Headers.build {
                append("Cache-Control", "no-cache")
                append("Connection", "keep-alive")
                append("X-Accel-Buffering", "no")
            }
            override suspend fun writeTo(channel: ByteWriteChannel) {
                try {
                    fun encode(chunk: ChatCompletionChunk) =
                        "data: ${json.encodeToString(ChatCompletionChunk.serializer(), chunk)}\n\n"
                    channel.writeFully(encode(ChatCompletionChunk(
                        completionId, created = created, model = req.model,
                        choices = listOf(ChunkChoice(delta = ChoiceDelta(role = "assistant")))
                    )).toByteArray())
                    channel.flush()
                    withTimeout(300_000) {
                        producer { delta ->
                            if (delta.isNotEmpty()) {
                                channel.writeFully(encode(ChatCompletionChunk(
                                    completionId, created = created, model = req.model,
                                    choices = listOf(ChunkChoice(delta = ChoiceDelta(content = delta)))
                                )).toByteArray())
                                channel.flush()
                            }
                        }
                    }
                    channel.writeFully(encode(ChatCompletionChunk(
                        completionId, created = created, model = req.model,
                        choices = listOf(ChunkChoice(delta = ChoiceDelta(), finish_reason = "stop"))
                    )).toByteArray())
                    channel.writeFully("data: [DONE]\n\n".toByteArray())
                    channel.flush()
                    GatewayModelHealth.record(req.model, true)
                } catch (e: Exception) {
                    GatewayModelHealth.record(req.model, false)
                    // 不裸断流：把错误作为 SSE 事件写出，客户端收到明确错误而非 EOFException
                    try {
                        val errJson = org.json.JSONObject().apply {
                            put("error", org.json.JSONObject().apply {
                                put("type", "upstream_error")
                                put("code", (e as? WebAutomationException)?.code ?: e.javaClass.simpleName)
                                put("message", e.message ?: e.toString())
                            })
                        }.toString()
                        channel.writeFully("data: $errJson\n\n".toByteArray())
                        channel.writeFully("data: [DONE]\n\n".toByteArray())
                        channel.flush()
                    } catch (_: Exception) {
                    }
                }
            }
        })
    }

    private suspend fun RoutingContext.handleKimiChat(
        req: ChatCompletionRequest,
        completionId: String,
        created: Int,
        provider: WebProviderType,
    ) {
        val token = KimiClient.extractToken(nextAuthorization(provider, kimiRotationIdx))
        if (token.isBlank()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "请先在浏览器登录 Kimi、发送一条消息并保存会话"))
            return
        }
        respondProviderText(req, completionId, created) { emit ->
            withContext(Dispatchers.IO) {
                KimiClient.chatCompletion(req.messages, token).use { response ->
                    KimiClient.decodeFrames(response.body!!.source(), emit) { }
                }
            }
        }
    }

    private fun deepSeekPrompt(messages: List<ChatMessage>): String = messages
        .filter { it.textContent().isNotBlank() }
        .joinToString("\n\n") { "[${it.role}]: ${it.textContent()}" }

    private suspend fun RoutingContext.handleDeepSeekChat(
        req: ChatCompletionRequest,
        completionId: String,
        created: Int,
    ) {
        val authorization = nextAuthorization(WebProviderType.DEEPSEEK_WEB, deepSeekRotationIdx)
            .trim().let { if (it.startsWith("Bearer ", true)) it else "Bearer $it" }
        if (authorization == "Bearer ") {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "请先在浏览器登录 DeepSeek、发送一条消息并保存会话"))
            return
        }
        val prompt = deepSeekPrompt(req.messages)
        if (prompt.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "empty prompt"))
            return
        }
        respondProviderText(req, completionId, created) { emit ->
            deepSeekRequestMutex.withLock {
                withContext(Dispatchers.IO) {
                    DeepSeekClient.token = authorization
                    val sessionId = DeepSeekClient.createSession()
                    try {
                        val challenge = DeepSeekClient.createPowChallenge("/api/v0/chat/completion")
                        val answer = DeepSeekPow.solve(
                            challenge.challenge, challenge.salt, challenge.expireAt, challenge.difficulty
                        )
                        val pow = DeepSeekClient.buildPowHeader(challenge, answer, "/api/v0/chat/completion")
                        val modelType = if (req.model.contains("reasoner", true)) "expert" else "default"
                        DeepSeekClient.chatCompletion(sessionId, prompt, pow, modelType).use { response ->
                            DeepSeekClient.parseSse(response.body!!.source(), emit)
                        }
                    } finally {
                        DeepSeekClient.deleteSession(sessionId)
                    }
                }
            }
        }
    }

    private suspend fun RoutingContext.handleQwenChat(
        model: ProviderModel,
        req: ChatCompletionRequest,
        completionId: String,
        created: Int,
    ) {
        val cookie = nextAuthorization(WebProviderType.QWEN_WEB, qwenRotationIdx)
        if (cookie.isBlank()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf(
                "error" to "千问需要网页登录一次：请到浏览器选择“千问 Web”，登录后保存会话"
            ))
            return
        }
        val prompt = deepSeekPrompt(req.messages)
        val deepSearch = if (req.model.contains("think", true)) "1" else "0"
        respondProviderText(req, completionId, created) { emit ->
            withContext(Dispatchers.IO) {
                var sessionId = ""
                QwenClient.chatCompletion(prompt, cookie, deepSearch, model.upstreamModelId).use { response ->
                    QwenClient.parseQwenSse(
                        response.body!!.source(),
                        onText = emit,
                        onSessionId = { sessionId = it },
                    )
                }
                if (sessionId.isNotBlank()) QwenClient.deleteSession(sessionId, cookie)
            }
        }
    }

    private suspend fun RoutingContext.handleZhipuChat(
        model: ProviderModel,
        req: ChatCompletionRequest,
        completionId: String,
        created: Int,
    ) {
        val prompt = deepSeekPrompt(req.messages)
        if (prompt.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "empty prompt"))
            return
        }
        respondProviderText(req, completionId, created) { emit ->
            withContext(Dispatchers.IO) {
                ZhipuClient.chatCompletion(
                    prompt,
                    plusModel = model.publicModelId.contains("plus", true),
                ).use { response -> ZhipuClient.parseSse(response.body!!.source(), emit) }
            }
        }
    }

    private suspend fun RoutingContext.handleCustomWebChat(
        model: ProviderModel,
        req: ChatCompletionRequest,
        completionId: String,
        created: Int,
    ) {
        val config = CustomWebProviderRegistry.find(model.accountId)
        if (config == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "自定义 Web 供应商配置不存在"))
            return
        }
        respondProviderText(req, completionId, created) { emit ->
            var previous = ""
            try {
                CustomWebChatAutomation.chat(config, req.messages) { complete ->
                    val delta = if (complete.startsWith(previous)) complete.removePrefix(previous) else complete
                    previous = complete
                    if (delta.isNotEmpty()) emit(delta)
                }
            } catch (e: WebAutomationException) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: e.code
                LogStore.e("CustomWeb", "${config.name} 网页转发失败：$message")
                throw e
            } catch (e: Exception) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                LogStore.e("CustomWeb", "${config.name} 网页转发异常：$message")
                throw e
            }
        }
    }

    private suspend fun RoutingContext.handleBuiltInBrowserChat(
        model: ProviderModel,
        req: ChatCompletionRequest,
        completionId: String,
        created: Int,
    ) {
        val config = builtInBrowserRelayConfig(model.providerType)
        respondProviderText(req, completionId, created) { emit ->
            var previous = ""
            try {
                CustomWebChatAutomation.chat(config, req.messages) { complete ->
                    val delta = if (complete.startsWith(previous)) complete.removePrefix(previous) else complete
                    previous = complete
                    if (delta.isNotEmpty()) emit(delta)
                }
            } catch (e: WebAutomationException) {
                val message = e.message?.takeIf(String::isNotBlank) ?: e.code
                LogStore.e("BrowserRelay", "${config.name}: $message")
                throw e
            }
        }
    }

    private suspend fun RoutingContext.handleOpenAiCompatibleChat(
        model: ProviderModel,
        req: ChatCompletionRequest,
        rawRequest: String
    ) {
        val candidates = if (model.accountId == "auto") {
            ApiRouteSelector.rank(ApiProviderRegistry.candidates(model.upstreamModelId))
        } else {
            listOfNotNull(ApiProviderRegistry.find(model.accountId)).filter { it.enabled }
        }
        if (candidates.isEmpty()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to mapOf(
                "type" to "provider_unavailable", "message" to "API provider is unavailable"
            )))
            return
        }

        var response: okhttp3.Response? = null
        var lastError = "所有同名模型线路均不可用"
        for (config in candidates) {
            val startedAt = System.nanoTime()
            try {
                val attempt = withContext(Dispatchers.IO) {
                    OpenAiCompatibleClient.executeChat(config, model.upstreamModelId, rawRequest)
                }
                if (attempt.isSuccessful && req.stream) {
                    val firstByteReady = withContext(Dispatchers.IO) {
                        attempt.body?.source()?.request(1) == true
                    }
                    if (!firstByteReady) {
                        attempt.close()
                        throw java.io.EOFException("上游流在首字节前结束")
                    }
                }
                val latency = (System.nanoTime() - startedAt) / 1_000_000
                val retryable = attempt.code == 429 || attempt.code >= 500
                ApiRouteSelector.record(config.id, attempt.isSuccessful, latency, retryable)
                if (attempt.isSuccessful || !retryable || config == candidates.last()) {
                    response = attempt
                    LogStore.i("AutoRoute", "${model.upstreamModelId} → ${config.name} (${latency}ms)")
                    break
                }
                lastError = "${config.name}: HTTP ${attempt.code}"
                attempt.close()
            } catch (e: Exception) {
                val latency = (System.nanoTime() - startedAt) / 1_000_000
                ApiRouteSelector.record(config.id, false, latency)
                lastError = "${config.name}: ${e.message ?: e.javaClass.simpleName}"
                LogStore.w("AutoRoute", "线路失败并切换：$lastError")
            }
        }
        val selected = response
        if (selected == null) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to mapOf(
                "type" to "all_routes_failed", "message" to lastError
            )))
            return
        }
        if (!selected.isSuccessful) {
            selected.use {
                call.respondText(
                    it.body?.string().orEmpty(),
                    ContentType.parse(it.header("Content-Type") ?: "application/json"),
                    HttpStatusCode.fromValue(it.code)
                )
            }
            return
        }
        if (!req.stream) {
            selected.use {
                call.respondText(
                    it.body?.string().orEmpty(),
                    ContentType.parse(it.header("Content-Type") ?: "application/json"),
                    HttpStatusCode.fromValue(it.code)
                )
            }
            return
        }
        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Text.EventStream
            override suspend fun writeTo(channel: ByteWriteChannel) {
                try {
                    selected.use { upstream ->
                        val source = upstream.body?.source()
                            ?: throw java.io.EOFException("Upstream response body is empty")
                        val buffer = okio.Buffer()
                        while (true) {
                            val read = source.read(buffer, 8192)
                            if (read == -1L) break
                            channel.writeFully(buffer.readByteArray())
                            channel.flush()
                        }
                    }
                    GatewayModelHealth.record(req.model, true)
                } catch (e: Exception) {
                    GatewayModelHealth.record(req.model, false)
                    throw e
                }
            }
        })
    }

    private suspend fun RoutingContext.handleChatGptWebChat(req: ChatCompletionRequest) {
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
        val created = (System.currentTimeMillis() / 1000).toInt()
        try {
            respondProviderText(req, completionId, created) { emit ->
                var previous = ""
                withTimeout(200_000) {
                    ChatGptWebChatAutomation.chat(req.messages) { complete ->
                        val delta = if (complete.startsWith(previous)) complete.removePrefix(previous) else complete
                        previous = complete
                        if (delta.isNotEmpty()) emit(delta)
                    }
                }
            }
        } catch (e: WebAutomationException) {
            LogStore.e("ChatGPTChat", "${e.code}: ${e.message}")
            if (!call.response.isSent) call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to mapOf(
                "type" to "webview_automation_error", "code" to e.code,
                "message" to (e.message ?: e.code)
            )))
        }
    }

    private suspend fun RoutingContext.handleGeminiWebChat(req: ChatCompletionRequest) {
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
        val created = (System.currentTimeMillis() / 1000).toInt()
        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                }
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    suspend fun emit(delta: ChoiceDelta, finish: String? = null) {
                        val chunk = ChatCompletionChunk(
                            completionId, created = created, model = req.model,
                            choices = listOf(ChunkChoice(delta = delta, finish_reason = finish))
                        )
                        channel.writeFully(("data: " + json.encodeToString(ChatCompletionChunk.serializer(), chunk) + "\n\n").toByteArray())
                        channel.flush()
                    }
                    emit(ChoiceDelta(role = "assistant"))
                    var sentText = ""
                    val sentImages = linkedSetOf<String>()
                    try {
                        withTimeout(200_000) {
                            GeminiWebChatAutomation.chat(req.messages) { update ->
                                val deltaText = if (update.text.startsWith(sentText)) update.text.removePrefix(sentText) else update.text
                                val deltaImages = update.images.filter(sentImages::add)
                                if (deltaText.isNotEmpty() || deltaImages.isNotEmpty()) {
                                    emit(ChoiceDelta(content = deltaText.takeIf(String::isNotEmpty), images = deltaImages))
                                }
                                sentText = update.text
                            }
                        }
                        emit(ChoiceDelta(), "stop")
                        channel.writeFully("data: [DONE]\n\n".toByteArray())
                        channel.flush()
                        GatewayModelHealth.record(req.model, true)
                    } catch (e: Exception) {
                        GatewayModelHealth.record(req.model, false)
                        LogStore.e("GeminiChat", "stream: ${e.message}")
                        throw e
                    }
                }
            })
            return
        }
        val result = try {
            withTimeout(200_000) { GeminiWebChatAutomation.chat(req.messages) }
        } catch (e: WebAutomationException) {
            LogStore.e("GeminiChat", "${e.code}: ${e.message}")
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to mapOf(
                "type" to "webview_automation_error", "code" to e.code,
                "message" to (e.message ?: e.code)
            )))
            return
        }
        call.respond(ChatCompletion(
            completionId, "chat.completion", created, req.model,
            listOf(Choice(message = Msg("assistant", result.text, result.images), finish_reason = "stop")),
            Usage(), ""
        ))
    }

    /** 上游非流式：拿到完整内容后，按客户端要求返回 JSON 或单块 SSE。 */
    private suspend fun RoutingContext.handleNonStreamUpstream(
        req: ChatCompletionRequest, model: String, body: String,
        completionId: String, created: Int
    ) {
        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    CnbClient.chatCompletion(body).use { resp ->
                        val text = resp.body!!.string()
                        val api = org.json.JSONObject(text)
                        val c = api.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content")
                        c to null
                    }
                }
            } catch (e: Exception) {
                LogStore.e("Proxy", "CNB 请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            GatewayModelHealth.record(req.model, false)
            LogStore.e("Proxy", "返回错误给客户端: $errMsg")
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        GatewayModelHealth.record(req.model, true)
        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    val sb = StringBuilder()
                    try {
                        val chunk = ChatCompletionChunk(
                            completionId, "chat.completion.chunk", created, model,
                            listOf(ChunkChoice(delta = ChoiceDelta(content = contentText)))
                        )
                        sb.append("data: ")
                            .append(json.encodeToString(ChatCompletionChunk.serializer(), chunk))
                            .append("\n\n")
                        val stop = ChatCompletionChunk(
                            completionId, "chat.completion.chunk", created, model,
                            listOf(ChunkChoice(delta = ChoiceDelta(), finish_reason = "stop"))
                        )
                        sb.append("data: ")
                            .append(json.encodeToString(ChatCompletionChunk.serializer(), stop))
                            .append("\n\n")
                        sb.append("data: [DONE]\n\n")
                        channel.writeFully(sb.toString().toByteArray())
                        channel.flush()
                    } catch (e: Exception) {
                        LogStore.e("Proxy", "流式写出异常: ${e.stackTraceToString()}")
                    }
                }
            })
        } else {
            LogStore.i("Proxy", "返回完成结果，长度=${contentText.length}")
            call.respond(
                ChatCompletion(
                    completionId, "chat.completion", created, model,
                    listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                    Usage(), ""
                )
            )
        }
    }

    /** 上游流式：直接透传 CNB 的 SSE；若客户端要非流式则累加后返回 JSON。 */
    private suspend fun RoutingContext.handleStreamUpstream(
        req: ChatCompletionRequest, model: String, body: String,
        completionId: String, created: Int
    ) {
        if (!req.stream) {
            val acc = StringBuilder()
            val errMsg = withContext(Dispatchers.IO) {
                try {
                    withTimeout(300_000) {
                        CnbClient.chatCompletion(body).use { resp ->
                            val src = resp.body!!.source()
                            parseCnbSse(src) { acc.append(it) }
                            null
                        }
                    }
                } catch (e: Exception) {
                    LogStore.e("Proxy", "CNB 流式请求异常: ${e.stackTraceToString()}")
                    e.localizedMessage ?: e.toString()
                }
            }
            if (errMsg != null) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
                return
            }
            call.respond(
                ChatCompletion(
                    completionId, "chat.completion", created, model,
                    listOf(Choice(message = Msg("assistant", acc.toString()), finish_reason = "stop")),
                    Usage(), ""
                )
            )
            return
        }

        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Text.EventStream
            override val headers = Headers.build {
                append("Cache-Control", "no-cache")
                append("Connection", "keep-alive")
                append("X-Accel-Buffering", "no")
            }

            override suspend fun writeTo(channel: ByteWriteChannel) {
                withContext(Dispatchers.IO) {
                    try {
                        withTimeout(300_000) {
                            CnbClient.chatCompletion(body).use { resp ->
                                val src = resp.body!!.source()
                                while (true) {
                                    val line = src.readUtf8Line() ?: break
                                    channel.writeFully((line + "\n").toByteArray())
                                    if (line.isEmpty()) channel.flush()
                                }
                            }
                        }
                        GatewayModelHealth.record(req.model, true)
                    } catch (e: Exception) {
                        GatewayModelHealth.record(req.model, false)
                        LogStore.e("Proxy", "流式转发异常: ${e.stackTraceToString()}")
                        throw e
                    }
                }
            }
        })
    }

    /** 解析 CNB 的 SSE，逐块回调 delta.content（多行 data 字段会拼接）。 */
    private fun parseCnbSse(src: okio.BufferedSource, onDelta: (String) -> Unit) {
        val data = StringBuilder()
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.isEmpty()) {
                val payload = data.toString().trim()
                data.setLength(0)
                if (payload.isNotEmpty() && payload != "[DONE]") {
                    try {
                        val o = org.json.JSONObject(payload)
                        val choices = o.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            delta?.optString("content")?.let { if (it.isNotEmpty()) onDelta(it) }
                        }
                    } catch (_: Exception) {
                    }
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trimStart()).append("\n")
            }
        }
    }

    // ── 豆包（Doubao）相关 ───────────────────────────────────

    private fun isDoubaoModel(m: String): Boolean {
        val lower = m.lowercase()
        return DOUBAO_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    /** 取最后一条 user 消息的文本作为豆包输入。 */
    private fun lastUserText(messages: List<ChatMessage>): String {
        for (i in messages.indices.reversed()) {
            if (messages[i].role == "user") return messages[i].textContent()
        }
        return messages.lastOrNull()?.textContent() ?: ""
    }

    /** OpenAI size（如 1024x1024）映射到豆包 ratio。 */
    private fun sizeToRatio(size: String): String? {
        return when (size) {
            "768x768", "1024x1024", "512x512", "256x256" -> "1:1"
            "768x1152", "1024x1792", "1024x1536" -> "9:16"
            "1152x768", "1792x1024", "1536x1024" -> "16:9"
            "1056x768", "1024x768" -> "4:3"
            "768x1024" -> "3:4"
            "768x1920" -> "2:5"
            "1920x768" -> "5:2"
            else -> null
        }
    }

    /** 解析生图比例：原始 ratio（如 9:16）直接返回，否则按 OpenAI size 映射，兜底 9:16。 */
    private fun resolveRatio(size: String): String {
        if (size.isBlank()) return "9:16"
        if (size.contains(":")) return size
        return sizeToRatio(size) ?: "9:16"
    }

    /**
     * 豆包聊天：上游恒为 SSE 流式。
     *  - 客户端要求流式 → 边解析边转发 OpenAI 格式 chunk
     *  - 客户端要求非流式 → 累加完整文本后返回单个 JSON
     */
    private suspend fun RoutingContext.handleDoubaoChat(
        req: ChatCompletionRequest, completionId: String, created: Int
    ) {
        val cookies = DoubaoClient.parseCookie(nextCookie())
        val text = lastUserText(req.messages)
        // 提取图片附件并上传到豆包图床（upload_image 拿 uri）
        val images = withContext(Dispatchers.IO) {
            req.messages.flatMap { it.attachments() }.mapNotNull { att ->
                val dataUrl = att.dataUrl
                if (!dataUrl.startsWith("data:")) return@mapNotNull null
                val mime = att.mime.ifBlank { "image/png" }
                val ext = when {
                    mime.contains("jpeg", true) || mime.contains("jpg", true) -> "jpeg"
                    mime.contains("webp", true) -> "webp"
                    else -> "png"
                }
                val base64 = dataUrl.substringAfter("base64,")
                val bytes = runCatching { android.util.Base64.decode(base64, android.util.Base64.NO_WRAP) }
                    .getOrNull() ?: return@mapNotNull null
                // 解码图片真实宽高（豆包 image_ori 需要非 0 尺寸才能识别图片）
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val width = if (bounds.outWidth > 0) bounds.outWidth else 0
                val height = if (bounds.outHeight > 0) bounds.outHeight else 0
                runCatching { DoubaoClient.uploadImage(bytes, ext, cookies, width, height) }
                    .getOrNull()
            }
        }
        LogStore.i("Doubao", "调用 /chat/completion，输入长度=${text.length}，图片=${images.size}")

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    try {
                        GatewayModelHealth.recordInvocation(req.model) {
                            withContext(Dispatchers.IO) {
                                withTimeout(300_000) {
                                    val resp = DoubaoClient.chatCompletion(text, cookies, images = images)
                                    try {
                                        parseDoubaoSse(resp.body!!.source()) { delta ->
                                            val chunk = ChatCompletionChunk(
                                                completionId, "chat.completion.chunk", created, req.model,
                                                listOf(ChunkChoice(delta = ChoiceDelta(content = delta)))
                                            )
                                            val sb = StringBuilder()
                                            sb.append("data: ")
                                                .append(json.encodeToString(ChatCompletionChunk.serializer(), chunk))
                                                .append("\n\n")
                                            channel.writeFully(sb.toString().toByteArray())
                                            channel.flush()
                                        }
                                    } finally {
                                        resp.close()
                                    }
                                    channel.writeFully("data: [DONE]\n\n".toByteArray())
                                    channel.flush()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LogStore.e("Doubao", "流式转发异常: ${e.stackTraceToString()}")
                        throw e
                    }
                }
            })
            return
        }

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    val resp = DoubaoClient.chatCompletion(text, cookies, images = images)
                    try {
                        val sb = StringBuilder()
                        parseDoubaoSse(resp.body!!.source()) { sb.append(it) }
                        sb.toString() to null
                    } finally {
                        resp.close()
                    }
                }
            } catch (e: Exception) {
                LogStore.e("Doubao", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            LogStore.e("Doubao", "返回错误给客户端: $errMsg")
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        LogStore.i("Doubao", "返回完成结果，长度=${contentText.length}")
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, req.model,
                listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    /** 生图接口：按 public model id 路由到 ChatGPT Web 自动操作或豆包。 */
    private suspend fun RoutingContext.handleImageGen(rawRequestOverride: String? = null) {
        try {
            val raw = rawRequestOverride ?: call.receiveText()
            val o = org.json.JSONObject(raw)
            val prompt = o.optString("prompt", "")
            val size = o.optString("size", "")
            val modelId = o.optString("model", "doubao-image-web")
            call.attributes.put(RequestedModelKey, modelId)
            val registered = ProviderCatalog.find(modelId)
            if (registered == null || ProviderCapability.IMAGE_GENERATION !in registered.capabilities) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to mapOf(
                    "type" to "invalid_model", "code" to "unknown_image_model",
                    "message" to "Unknown image model: $modelId"
                )))
                return
            }
            LogStore.i(
                "ImageRoute",
                "$modelId → ${registered.providerType.name}/${registered.accountId.orEmpty()}"
            )
            if (registered.providerType == WebProviderType.OPENAI_COMPATIBLE) {
                val candidates = if (registered.accountId == "auto") {
                    ApiRouteSelector.rank(ApiProviderRegistry.candidates(registered.upstreamModelId, image = true))
                } else {
                    listOfNotNull(ApiProviderRegistry.find(registered.accountId)).filter { it.enabled }
                }
                if (candidates.isEmpty()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "API provider is unavailable"))
                    return
                }

                var response: okhttp3.Response? = null
                var lastError = "所有同名生图线路均不可用"
                for (config in candidates) {
                    val startedAt = System.nanoTime()
                    try {
                        val attempt = withContext(Dispatchers.IO) {
                            OpenAiCompatibleClient.executeImage(config, registered.upstreamModelId, raw)
                        }
                        val latency = (System.nanoTime() - startedAt) / 1_000_000
                        val retryable = attempt.code == 429 || attempt.code >= 500
                        ApiRouteSelector.record(config.id, attempt.isSuccessful, latency, retryable)
                        if (attempt.isSuccessful || !retryable || config == candidates.last()) {
                            response = attempt
                            LogStore.i("AutoRoute", "生图 ${registered.upstreamModelId} → ${config.name} (${latency}ms)")
                            break
                        }
                        lastError = "${config.name}: HTTP ${attempt.code}"
                        attempt.close()
                    } catch (e: Exception) {
                        val latency = (System.nanoTime() - startedAt) / 1_000_000
                        ApiRouteSelector.record(config.id, false, latency)
                        lastError = "${config.name}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                val selected = response
                if (selected == null) {
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to mapOf(
                        "type" to "all_routes_failed", "message" to lastError
                    )))
                    return
                }
                selected.use {
                    call.respondText(
                        it.body?.string().orEmpty(),
                        ContentType.parse(it.header("Content-Type") ?: "application/json"),
                        HttpStatusCode.fromValue(it.code)
                    )
                }
                return
            }
            if (registered.providerType == WebProviderType.CHATGPT_WEB) {
                try {
                    val urls = ChatGptWebAutomation.generateImage(prompt)
                    call.respond(ImageGenResponse(
                        created = (System.currentTimeMillis() / 1000).toInt(),
                        data = urls.map(::ImageDataItem)
                    ))
                } catch (e: WebAutomationException) {
                    LogStore.e("ChatGPTImage", "${e.code}: ${e.message}")
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to mapOf(
                        "type" to "webview_automation_error", "code" to e.code,
                        "message" to (e.message ?: e.code)
                    )))
                }
                return
            }
            if (registered.providerType == WebProviderType.GEMINI_WEB) {
                try {
                    val ratio = resolveRatio(if (size.isNotBlank()) size else defaultImageSize)
                    val urls = GeminiWebImageAutomation.generateImage(prompt, ratio)
                    call.respond(ImageGenResponse(
                        created = (System.currentTimeMillis() / 1000).toInt(),
                        data = urls.map(::ImageDataItem)
                    ))
                } catch (e: WebAutomationException) {
                    LogStore.e("GeminiImage", "${e.code}: ${e.message}")
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to mapOf(
                        "type" to "webview_automation_error", "code" to e.code,
                        "message" to (e.message ?: e.code)
                    )))
                }
                return
            }
            if (registered.providerType == WebProviderType.ZHIPU_GUEST) {
                try {
                    val ratio = resolveRatio(if (size.isNotBlank()) size else defaultImageSize)
                    val urls = withContext(Dispatchers.IO) { ZhipuClient.generateImage(prompt, ratio) }
                    if (urls.isEmpty()) {
                        call.respond(HttpStatusCode.BadGateway, mapOf("error" to "智谱生图未返回图片"))
                    } else {
                        call.respond(ImageGenResponse(
                            created = (System.currentTimeMillis() / 1000).toInt(),
                            data = urls.map(::ImageDataItem),
                        ))
                    }
                } catch (e: Exception) {
                    LogStore.e("ZhipuImage", e.stackTraceToString())
                    call.respond(HttpStatusCode.BadGateway, mapOf(
                        "error" to (e.localizedMessage ?: "智谱生图失败")
                    ))
                }
                return
            }
            if (registered.providerType == WebProviderType.RAPHAEL_WEB) {
                try {
                    val ratio = resolveRatio(if (size.isNotBlank()) size else defaultImageSize)
                    val urls = RaphaelWebImageAutomation.generateImage(prompt, ratio)
                    call.respond(ImageGenResponse(
                        created = (System.currentTimeMillis() / 1000).toInt(),
                        data = urls.map(::ImageDataItem)
                    ))
                } catch (e: WebAutomationException) {
                    LogStore.e("RaphaelImage", "${e.code}: ${e.message}")
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to mapOf(
                        "type" to "webview_automation_error", "code" to e.code,
                        "message" to (e.message ?: e.code)
                    )))
                }
                return
            }
            if (registered.providerType == WebProviderType.CUSTOM_WEB) {
                val config = CustomWebProviderRegistry.find(registered.accountId)
                if (config == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "自定义 Web 生图配置不存在"))
                    return
                }
                try {
                    val ratio = resolveRatio(if (size.isNotBlank()) size else defaultImageSize)
                    val urls = CustomWebImageAutomation.generateImage(config, prompt, ratio)
                    call.respond(ImageGenResponse(
                        created = (System.currentTimeMillis() / 1000).toInt(),
                        data = urls.map(::ImageDataItem)
                    ))
                } catch (e: WebAutomationException) {
                    LogStore.e("CustomWebImage", "${e.code}: ${e.message}")
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to mapOf(
                        "type" to "webview_automation_error", "code" to e.code,
                        "message" to (e.message ?: e.code)
                    )))
                }
                return
            }
            // 外部指定且可识别 → 用之；否则用设置界面尺寸；再不行兜底 9:16 竖版
            val ratio = resolveRatio(if (size.isNotBlank()) size else defaultImageSize)
            val cookies = DoubaoClient.parseCookie(nextCookie())
            LogStore.i("Doubao", "调用生图，prompt=${prompt.take(60)} ratio=$ratio")

            val (result, errMsg) = withContext(Dispatchers.IO) {
                try {
                    DoubaoClient.generateImage(prompt, cookies, ratio) to null
                } catch (e: Exception) {
                    LogStore.e("Doubao", "生图异常: ${e.stackTraceToString()}")
                    null to (e.localizedMessage ?: e.toString())
                }
            }
            if (errMsg != null) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
                return
            }
            val data = (result?.images ?: emptyList()).map {
                ImageDataItem(url = if (it.rawUrl.isNotBlank()) it.rawUrl else it.oriUrl)
            }
            if (data.isEmpty()) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "豆包生图未返回图片"))
                return
            }
            call.respond(
                ImageGenResponse(
                    created = (System.currentTimeMillis() / 1000).toInt(),
                    data = data
                )
            )
        } catch (e: Exception) {
            LogStore.e("Doubao", "生图处理失败: ${e.stackTraceToString()}")
            if (!call.response.isSent) {
                try {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.localizedMessage ?: e.toString()))
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 解析豆包 SSE，逐块回调文本增量（思考/正文均作为 content 输出）。 */
    private suspend fun parseDoubaoSse(src: okio.BufferedSource, onDelta: suspend (String) -> Unit) {
        val data = StringBuilder()
        // 深度思考状态：thinking_block(block_type=10040) is_finish=false 表示思考中，
        // 期间的顶层 text 是思考链(CoT)，跳过以与豆包网页端(折叠 CoT 只显示正文)一致。
        var inThinking = false
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.isEmpty()) {
                val payload = data.toString().trim()
                data.setLength(0)
                if (payload.isEmpty()) continue
                try {
                    val obj = org.json.JSONObject(payload)
                    if (obj.has("error_code") && obj.optInt("error_code") != 0) {
                        val code = obj.optInt("error_code")
                        if (code == 710022002 || code == 710022004) {
                            throw RuntimeException("豆包限流($code): ${obj.optString("error_msg")}")
                        }
                        LogStore.e("Doubao", "error_code=$code ${obj.optString("error_msg")}")
                        return
                    }
                    // 直接文本字段（思考链期间的 text 跳过）
                    if (!inThinking && obj.has("text") && obj.optString("text", "").isNotEmpty()) {
                        onDelta(obj.getString("text"))
                    }
                    // content_block 解析
                    val blocks = mutableListOf<org.json.JSONObject>()
                    val patchOps = obj.optJSONArray("patch_op")
                    if (patchOps != null) {
                        for (i in 0 until patchOps.length()) {
                            val pv = patchOps.optJSONObject(i)?.optJSONObject("patch_value") ?: continue
                            val cb = pv.optJSONArray("content_block") ?: continue
                            for (j in 0 until cb.length()) blocks.add(cb.getJSONObject(j))
                        }
                    }
                    val contentObj = obj.optJSONObject("content")
                    if (contentObj != null) {
                        val cb = contentObj.optJSONArray("content_block")
                        if (cb != null) for (j in 0 until cb.length()) blocks.add(cb.getJSONObject(j))
                    }
                    for (cb in blocks) {
                        val bt = cb.optInt("block_type", 0)
                        val c = cb.optJSONObject("content") ?: org.json.JSONObject()
                        when (bt) {
                            10040 -> {
                                // thinking_block：is_finish=false 进入思考，true 结束思考
                                inThinking = !cb.optBoolean("is_finish", true)
                            }
                            10000 -> {
                                // 思考链(CoT)是挂在 block_type=10040 thinking_block 下的 text_block
                                // （有 parent_id），正文 text_block 无 parent_id。跳过带 parent_id 的，
                                // 避免把思考过程混入正文（与豆包网页端折叠 CoT 只显示正文一致）。
                                if (cb.optString("parent_id", "").isEmpty()) {
                                    val t = c.optJSONObject("text_block")?.optString("text", "")
                                    if (!t.isNullOrEmpty()) onDelta(t)
                                }
                            }
                            10101 -> {
                                val t = c.optJSONObject("loading_block")?.optJSONObject("text_loading")?.optString("text", "")
                                if (!t.isNullOrEmpty()) onDelta(t)
                            }
                            10024 -> {
                                val t = c.optJSONObject("generic_tool_block")?.optString("title", "")
                                if (!t.isNullOrEmpty()) onDelta("[tool] $t")
                            }
                        }
                    }
                    // patch_value.content 字符串内嵌 JSON。只取正文（patch_object=102），
                    // 跳过思考链 patch_object=103（ThinkingContent）等，避免把 CoT 混入正文。
                    if (patchOps != null) {
                        for (i in 0 until patchOps.length()) {
                            val po = patchOps.optJSONObject(i) ?: continue
                            if (po.optInt("patch_object", 0) != 102) continue
                            val pv = po.optJSONObject("patch_value") ?: continue
                            val pvContent = pv.optString("content", "")
                            if (pvContent.isNotEmpty()) {
                                try {
                                    val co = org.json.JSONObject(pvContent)
                                    val t = co.optString("text", "")
                                    if (t.isNotEmpty()) onDelta(t)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e.message?.startsWith("豆包限流") == true) throw e
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trimStart()).append("\n")
            }
        }
    }
}
