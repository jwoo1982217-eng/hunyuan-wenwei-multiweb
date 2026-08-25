package com.wenwei.deepseekproxy

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import com.wenwei.deepseekproxy.proxy.LogStore
import com.wenwei.deepseekproxy.proxy.LocalGatewayTokenStore
import com.wenwei.deepseekproxy.proxy.LocalGatewayServiceLifecycle
import com.wenwei.deepseekproxy.proxy.ProxyServer
import com.wenwei.deepseekproxy.proxy.SavedWebSession
import com.wenwei.deepseekproxy.proxy.WebProviderType
import com.wenwei.deepseekproxy.proxy.WebSessionStore
import com.wenwei.deepseekproxy.proxy.ChatGptWebClient
import com.wenwei.deepseekproxy.proxy.ProviderCapability
import com.wenwei.deepseekproxy.proxy.ProviderCatalog
import com.wenwei.deepseekproxy.proxy.ProviderModel
import com.wenwei.deepseekproxy.proxy.ApiProviderConfig
import com.wenwei.deepseekproxy.proxy.ApiProviderRegistry
import com.wenwei.deepseekproxy.proxy.ApiProviderStore
import com.wenwei.deepseekproxy.proxy.OpenAiCompatibleClient
import com.wenwei.deepseekproxy.proxy.CloudflareWorkersAiClient
import com.wenwei.deepseekproxy.proxy.ApiRouteSelector
import com.wenwei.deepseekproxy.proxy.CustomWebProviderConfig
import com.wenwei.deepseekproxy.proxy.CustomWebProviderRegistry
import com.wenwei.deepseekproxy.proxy.CustomWebProviderStore
import com.wenwei.deepseekproxy.proxy.customWebImageModelId
import com.wenwei.deepseekproxy.proxy.customWebVideoModelId

/** 一条聊天消息：可含文本和/或图片 URL 列表。 */
data class ChatMessageItem(
    val role: String,
    val text: String = "",
    val images: List<String> = emptyList(),
    val videos: List<String> = emptyList()
)

private data class RealAttachment(val name: String, val mime: String, val dataUrl: String, val text: String?)
private data class LocalChatReply(val text: String, val images: List<String> = emptyList())

internal fun buildRoundtableSynthesisPrompt(
    question: String,
    answers: List<Pair<String, String>>
): String = buildString {
    append("你是多模型圆桌的总结者。请基于各模型的可见回答，列出共识、分歧、风险，最后给出可执行结论。")
    append("不要披露或推测任何隐藏思维过程。\n\n原问题：").append(question)
    answers.forEach { (model, answer) ->
        append("\n\n【").append(model).append("】\n").append(answer)
    }
}

/** 生图比例选项：展示名 → 豆包 ratio 串。
 *  竖版（含默认 9:16）排在前，横版在后。高清/低清尺寸对豆包无效，仅比例生效。 */
val IMAGE_SIZE_OPTIONS = listOf(
    "9:16 (竖屏)" to "9:16",
    "3:4 (竖版)" to "3:4",
    "1:1 (方形)" to "1:1",
    "16:9 (横屏)" to "16:9",
    "4:3 (横版)" to "4:3",
    "2:5 (长竖屏)" to "2:5",
    "5:2 (长横屏)" to "5:2"
)

class ProxyViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ds_proxy", Context.MODE_PRIVATE)
    private val localGatewayTokenStore = LocalGatewayTokenStore(app)
    private val gatewayStartMutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        // ChatGPT Web image generation can legitimately finish after two minutes,
        // and WebView video generation can take up to ten. Keep the local relay
        // client alive longer than the web automation deadline.
        .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val webSessionStore = WebSessionStore(app)
    private val apiProviderStore = ApiProviderStore(app)
    private val customWebProviderStore = CustomWebProviderStore(app)
    val customWebProviders = MutableStateFlow(customWebProviderStore.load())
    val webSessions = MutableStateFlow(webSessionStore.load())
    val webSessionStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val webModels = MutableStateFlow(buildWebModels(webSessions.value))
    val imageModel = MutableStateFlow(prefs.getString("image_model", "gpt-image-2") ?: "gpt-image-2")
    val videoModel = MutableStateFlow(prefs.getString("video_model", "") ?: "")
    val apiProviders = MutableStateFlow(apiProviderStore.load())
    val apiProviderStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val providerModels = MutableStateFlow<List<ProviderModel>>(emptyList())
    val localBearerToken = MutableStateFlow(localGatewayTokenStore.getOrCreate())

    init {
        // Legacy builds persisted a running flag and restored the foreground service.
        // Local gateway startup is intentionally user-only now.
        prefs.edit().remove("running").apply()
        LocalGatewayServiceLifecycle.reconcileWithRuntime()
        ProxyServer.setWebSessions(webSessions.value)
        CustomWebProviderRegistry.set(customWebProviders.value)
        refreshProviderRegistry()
        
        // 验证保存的 imageModel 是否有效，如果无效则重置为第一个可用的生图模型
        val savedImageModel = imageModel.value
        val isValid = ProviderCatalog.find(savedImageModel)?.let { 
            ProviderCapability.IMAGE_GENERATION in it.capabilities 
        } ?: false
        
        if (!isValid) {
            val fallback = ProviderCatalog.imageModels.firstOrNull()?.publicModelId ?: "gpt-image-2"
            android.util.Log.w("ProxyViewModel", "保存的 imageModel=$savedImageModel 无效，回退到 $fallback")
            setImageModel(fallback)
        } else {
            android.util.Log.i("ProxyViewModel", "imageModel=$savedImageModel 验证通过")
        }
        
        if (imageModel.value == "image-web" &&
            customWebProviders.value.any { it.modelId == "image-dddd-image-web" }
        ) {
            setImageModel("image-dddd-image-web")
        }
    }

    private fun providerSlug(config: ApiProviderConfig): String = config.name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "api-${config.id.take(8)}" }

    private fun buildApiModels(configs: List<ApiProviderConfig>): List<ProviderModel> = configs
        .filter { it.enabled }.flatMap { config ->
            val prefix = providerSlug(config)
            (config.chatModels + config.imageModels + config.videoModels).distinctBy { it.lowercase() }.map { upstream ->
                val inChat = upstream in config.chatModels
                val inImage = upstream in config.imageModels
                val inVideo = upstream in config.videoModels
                // 聊天模型名含 image（gpt-image / sdxl-image / flux-image 等）自动归生图，
                // 不再出现在聊天列表里（分开放）。
                val isImageName = Regex("image|dall|flux|sdxl|stable[-_]?diffusion|seedream|imagen|cogview|draw|绘画|生图|图生图", RegexOption.IGNORE_CASE)
                    .containsMatchIn(upstream)
                val capabilities = buildSet {
                    if (inChat && !isImageName) {
                        add(ProviderCapability.CHAT)
                        add(ProviderCapability.STREAMING)
                    }
                    if (inImage || isImageName) add(ProviderCapability.IMAGE_GENERATION)
                    if (inVideo) add(ProviderCapability.VIDEO_GENERATION)
                }
                ProviderModel(
                    publicModelId = "$prefix--$upstream", displayName = upstream,
                    providerType = WebProviderType.OPENAI_COMPATIBLE,
                    capabilities = capabilities, upstreamModelId = upstream, accountId = config.id
                )
            }
        }

    private fun buildAutoModels(configs: List<ApiProviderConfig>): List<ProviderModel> {
        val enabled = configs.filter { it.enabled }
        val chat = enabled.flatMap { it.chatModels }.groupBy(String::lowercase)
            .filterValues { ids -> ids.size >= 2 }.map { (_, ids) ->
                val upstream = ids.first()
                ProviderModel(
                    publicModelId = "auto--$upstream",
                    displayName = "$upstream（自动最快）",
                    providerType = WebProviderType.OPENAI_COMPATIBLE,
                    capabilities = setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
                    upstreamModelId = upstream,
                    accountId = "auto"
                )
            }
        val images = enabled.flatMap { it.imageModels }.groupBy(String::lowercase)
            .filterValues { ids -> ids.size >= 2 }.map { (_, ids) ->
                val upstream = ids.first()
                ProviderModel(
                    publicModelId = "auto-image--$upstream",
                    displayName = "$upstream（自动最快）",
                    providerType = WebProviderType.OPENAI_COMPATIBLE,
                    capabilities = setOf(ProviderCapability.IMAGE_GENERATION),
                    upstreamModelId = upstream,
                    accountId = "auto"
                )
            }
        return chat + images
    }

    /** Lets OpenAI-compatible clients use the upstream model id directly. */
    private fun buildPlainModelAliases(configs: List<ApiProviderConfig>): List<ProviderModel> {
        val enabled = configs.filter { it.enabled }
        val upstreamIds = enabled.flatMap { it.chatModels + it.imageModels + it.videoModels }
            .distinctBy(String::lowercase)
        return upstreamIds.mapNotNull { upstream ->
            if (ProviderCatalog.models.any { it.publicModelId.equals(upstream, ignoreCase = true) }) {
                return@mapNotNull null
            }
            val chatProviders = enabled.filter { config ->
                config.chatModels.any { it.equals(upstream, ignoreCase = true) }
            }
            val imageProviders = enabled.filter { config ->
                config.imageModels.any { it.equals(upstream, ignoreCase = true) }
            }
            val videoProviders = enabled.filter { config ->
                config.videoModels.any { it.equals(upstream, ignoreCase = true) }
            }
            val providers = (chatProviders + imageProviders + videoProviders).distinctBy { it.id }
            // 聊天模型名含 image 自动归生图（分开放），不再作为聊天模型出现
            val isImageName = chatProviders.isNotEmpty() &&
                Regex("image|dall|flux|sdxl|stable[-_]?diffusion|seedream|imagen|cogview|draw|绘画|生图|图生图", RegexOption.IGNORE_CASE)
                    .containsMatchIn(upstream)
            val capabilities = buildSet {
                if (chatProviders.isNotEmpty() && !isImageName) {
                    add(ProviderCapability.CHAT)
                    add(ProviderCapability.STREAMING)
                }
                if (imageProviders.isNotEmpty() || isImageName) add(ProviderCapability.IMAGE_GENERATION)
                if (videoProviders.isNotEmpty()) add(ProviderCapability.VIDEO_GENERATION)
            }
            ProviderModel(
                publicModelId = upstream,
                displayName = upstream + if (chatProviders.size > 1 || imageProviders.size > 1) "（自动最快）" else "",
                providerType = WebProviderType.OPENAI_COMPATIBLE,
                capabilities = capabilities,
                upstreamModelId = upstream,
                accountId = providers.singleOrNull()?.id ?: "auto"
            )
        }
    }

    private fun refreshProviderRegistry() {
        ApiProviderRegistry.set(apiProviders.value)
        val customModels = customWebProviders.value.flatMap { config ->
            val primary = ProviderModel(
                publicModelId = config.modelId,
                displayName = config.name,
                providerType = WebProviderType.CUSTOM_WEB,
                capabilities = if (config.imageProvider) {
                    setOf(ProviderCapability.IMAGE_GENERATION)
                } else {
                    setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING)
                },
                accountId = config.id,
            )
            if (!config.imageProvider) {
                listOf(
                    primary,
                    ProviderModel(
                        publicModelId = customWebImageModelId(config),
                        displayName = "${config.name} 生图",
                        providerType = WebProviderType.CUSTOM_WEB,
                        capabilities = setOf(ProviderCapability.IMAGE_GENERATION),
                        accountId = config.id,
                    ),
                    ProviderModel(
                        publicModelId = customWebVideoModelId(config),
                        displayName = "${config.name} 视频",
                        providerType = WebProviderType.CUSTOM_WEB,
                        capabilities = setOf(ProviderCapability.VIDEO_GENERATION),
                        accountId = config.id,
                    )
                )
            } else listOf(primary)
        }
        providerModels.value = (webModels.value + customModels + buildApiModels(apiProviders.value) +
            buildPlainModelAliases(apiProviders.value) + buildAutoModels(apiProviders.value))
            .distinctBy { it.publicModelId }
        ProviderCatalog.setRuntimeModels(providerModels.value)
    }

    fun saveCustomWebProvider(config: CustomWebProviderConfig) {
        customWebProviderStore.save(config)
        customWebProviders.value = customWebProviderStore.load()
        CustomWebProviderRegistry.set(customWebProviders.value)
        refreshProviderRegistry()
        LogStore.i("Browser", "已保存自定义网页登录：${config.name}")
    }

    fun deleteCustomWebProvider(id: String) {
        customWebProviderStore.delete(id)
        customWebProviders.value = customWebProviderStore.load()
        CustomWebProviderRegistry.set(customWebProviders.value)
        refreshProviderRegistry()
    }

    private fun buildWebModels(sessions: List<SavedWebSession>): List<ProviderModel> = sessions.flatMap { session ->
        session.modelIds.map { upstreamId ->
            ProviderModel(
                publicModelId = "${session.providerType.name.lowercase()}--$upstreamId",
                displayName = upstreamId,
                providerType = session.providerType,
                capabilities = setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
                // Discovered web model IDs are upstream hints. ChatGPT uses its
                // currently selected web model, while Gemini web chat is now routable.
                enabled = session.providerType != WebProviderType.CHATGPT_WEB,
                upstreamModelId = upstreamId,
                accountId = session.id
            )
        }
    }

    private fun refreshWebSessions() {
        webSessions.value = webSessionStore.load()
        ProxyServer.setWebSessions(webSessions.value)
        webModels.value = buildWebModels(webSessions.value)
        refreshProviderRegistry()
    }

    fun saveApiProvider(config: ApiProviderConfig) {
        apiProviderStore.save(config)
        apiProviders.value = apiProviderStore.load()
        refreshProviderRegistry()
        LogStore.i("API", "已保存接口：${config.name}（密钥已隐藏）")
        testApiProvider(config, syncModels = true)
    }

    fun deleteApiProvider(id: String) {
        apiProviderStore.delete(id)
        apiProviders.value = apiProviderStore.load()
        refreshProviderRegistry()
    }

    fun testApiProvider(config: ApiProviderConfig, syncModels: Boolean = false) {
        apiProviderStatus.value = apiProviderStatus.value + (config.id to if (syncModels) "同步中…" else "测试中…")
        viewModelScope.launch(Dispatchers.IO) {
            val result = OpenAiCompatibleClient.test(config)
            ApiRouteSelector.record(config.id, result.success, result.latencyMs)
            if (result.success && syncModels) {
                val selectedChatBelongsToProvider = ProviderCatalog.find(chatModel.value)?.accountId == config.id
                val selectedImageBelongsToProvider = ProviderCatalog.find(imageModel.value)?.accountId == config.id
                // 归并规则：聊天模型里名字含 image 的模型自动挪到生图模型（分开放），
                // 名字含 video 的挪到视频模型，避免「含 image/video 的模型留在聊天列表」。
                fun isImageModel(id: String): Boolean =
                    id.contains("image", ignoreCase = true) ||
                        Regex("dall|flux|sdxl|stable[-_]?diffusion|seedream|imagen|cogview", RegexOption.IGNORE_CASE).containsMatchIn(id)
                fun isVideoModel(id: String): Boolean =
                    id.contains("video", ignoreCase = true) ||
                        Regex("sora|kling|wanx|veo|t2v|v2v|text[-_]?to[-_]?video|animate|cogvideo", RegexOption.IGNORE_CASE).containsMatchIn(id)
                val mergedChat = (config.chatModels + result.chatModels).distinct()
                val movedToImage = mergedChat.filter(::isImageModel)
                val movedToVideo = mergedChat.filter(::isVideoModel).filterNot(::isImageModel)
                val updated = config.copy(
                    // A number of compatible relays expose an incomplete /models
                    // response and do not declare image capabilities. Refreshing
                    // must therefore be additive: never make a user's saved model
                    // disappear merely because one discovery response omitted it.
                    chatModels = mergedChat.filterNot { isImageModel(it) || isVideoModel(it) },
                    imageModels = (config.imageModels + result.imageModels + movedToImage).distinct(),
                    videoModels = (config.videoModels + result.videoModels + movedToVideo).distinct()
                )
                apiProviderStore.save(updated)
                apiProviders.value = apiProviderStore.load()
                refreshProviderRegistry()
                if (selectedChatBelongsToProvider && ProviderCatalog.find(chatModel.value) == null) {
                    updated.chatModels.firstOrNull()?.let { setChatModel("${providerSlug(updated)}--$it") }
                }
                if (selectedImageBelongsToProvider && ProviderCatalog.find(imageModel.value) == null) {
                    updated.imageModels.firstOrNull()?.let { setImageModel("${providerSlug(updated)}--$it") }
                }
                apiProviderStatus.value = apiProviderStatus.value + (config.id to
                    "已同步：聊天 ${updated.chatModels.size} 个，生图 ${updated.imageModels.size} 个，视频 ${updated.videoModels.size} 个（保留原配置）")
            } else {
                apiProviderStatus.value = apiProviderStatus.value + (config.id to result.message)
            }
            LogStore.i("API", "${config.name}：${result.message}")
        }
    }

    fun testAllApiProviders() {
        apiProviders.value.filter { it.enabled }.forEach { testApiProvider(it, syncModels = false) }
    }

    fun saveWebSession(
        provider: WebProviderType,
        name: String,
        cookieHeader: String,
        authToken: String = "",
        cookieHeadersByOrigin: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val saved = webSessionStore.save(
            provider,
            name,
            cookieHeader,
            authToken,
            cookieHeadersByOrigin,
            extraHeaders,
        )
        refreshWebSessions()
        if (provider == WebProviderType.DOUBAO_WEB) updateSelectedCookie(saved.cookieHeader)
        LogStore.i("Browser", "已保存 ${provider.displayName} 会话：${saved.displayName}（凭证已隐藏）")
    }

    fun deleteWebSession(id: String) {
        webSessionStore.delete(id)
        refreshWebSessions()
    }

    fun updateWebSessionStatus(id: String, message: String) {
        webSessionStatus.value = webSessionStatus.value + (id to message)
        LogStore.i("Browser", "Gemini 会话页面验证：$message")
    }

    fun setImageModel(model: String) {
        imageModel.value = model
        prefs.edit().putString("image_model", model).apply()
    }

    fun setVideoModel(model: String) {
        videoModel.value = model
        prefs.edit().putString("video_model", model).apply()
    }

    fun testWebSession(session: SavedWebSession) {
        webSessionStatus.value = webSessionStatus.value + (session.id to "验证中…")
        viewModelScope.launch(Dispatchers.IO) {
            val result = when (session.providerType) {
                WebProviderType.CHATGPT_WEB -> ChatGptWebClient().testSession(session)
                WebProviderType.DOUBAO_WEB -> com.wenwei.deepseekproxy.proxy.WebSessionTestResult(
                    com.wenwei.deepseekproxy.proxy.WebCookieSnapshot.hasDoubaoLoginCookie(session.cookieHeader),
                    if (com.wenwei.deepseekproxy.proxy.WebCookieSnapshot.hasDoubaoLoginCookie(session.cookieHeader))
                        "豆包登录凭证完整，可通过聊天请求使用"
                    else "保存的只是访客 Cookie，请重新登录后保存会话"
                )
                WebProviderType.GEMINI_WEB -> com.wenwei.deepseekproxy.proxy.WebSessionTestResult(
                    session.cookieHeadersByOrigin.isNotEmpty(),
                    if (session.cookieHeadersByOrigin.isNotEmpty())
                        "Gemini 按域会话已保存；请载入后在 Gemini 主站完成页面验证"
                    else "旧版 Gemini 会话缺少 Cookie 域信息，请重新登录并保存"
                )
                WebProviderType.KIMI_WEB, WebProviderType.DEEPSEEK_WEB -> {
                    val ready = session.authToken.isNotBlank()
                    com.wenwei.deepseekproxy.proxy.WebSessionTestResult(
                        ready,
                        if (ready) "授权已保存，可通过本地中转调用"
                        else "尚未捕获授权；请登录后先发送一条消息，再保存会话"
                    )
                }
                else -> com.wenwei.deepseekproxy.proxy.WebSessionTestResult(false, "该Provider不支持网页登录验证")
            }
            webSessionStatus.value = webSessionStatus.value + (session.id to result.message)
            if (result.success && result.modelIds.isNotEmpty()) {
                webSessionStore.updateModels(session.id, result.modelIds)
                refreshWebSessions()
            }
            LogStore.i("Browser", "${session.providerType.displayName} 会话验证：${result.message}")
        }
    }

    /** 豆包 Cookie 列表（索引 0 为「默认Cookie」），支持多个并自动轮换。 */
    val cookies = MutableStateFlow(loadCookies())
    /** 当前选中的 Cookie 下标（浏览器提取会写入此下标）。 */
    val selectedIndex = MutableStateFlow(prefs.getInt("doubao_cookie_selected", 0).coerceAtLeast(0))

    private fun loadCookies(): List<String> {
        // 兼容旧版：曾用单条 doubao_cookie / token 存储
        val legacy = prefs.getString("doubao_cookie", null) ?: prefs.getString("token", null)
        val raw = prefs.getString("doubao_cookies", null)
        return if (raw != null) {
            try {
                val arr = org.json.JSONArray(raw)
                val list = (0 until arr.length()).map { arr.optString(it, "") }
                if (list.isEmpty()) listOf("") else list
            } catch (_: Exception) { listOf(legacy ?: "") }
        } else if (!legacy.isNullOrEmpty()) {
            listOf(legacy)
        } else {
            listOf("")
        }
    }

    private fun persistCookies() {
        val arr = org.json.JSONArray()
        cookies.value.forEach { arr.put(it) }
        prefs.edit().putString("doubao_cookies", arr.toString()).apply()
    }

    /** 当前选中 Cookie 的展示名（默认Cookie / Cookie N）。 */
    fun cookieLabel(i: Int): String = if (i == 0) "默认Cookie" else "Cookie ${i + 1}"

    /** 当前选中的 Cookie 文本。 */
    fun currentCookie(): String = cookies.value.getOrNull(selectedIndex.value) ?: ""

    /** 选中某个 Cookie（点击弹窗中的某一项）。 */
    fun setSelectedIndex(i: Int) {
        if (i in cookies.value.indices) {
            selectedIndex.value = i
            prefs.edit().putInt("doubao_cookie_selected", i).apply()
        }
    }

    /** 更新当前选中的 Cookie 文本（输入框编辑 / 浏览器提取）。 */
    fun updateSelectedCookie(value: String) {
        val i = selectedIndex.value
        val list = cookies.value.toMutableList()
        if (i in list.indices) list[i] = value else list.add(value)
        cookies.value = list
        persistCookies()
        ProxyServer.setCookieList(list)
    }

    /** 新增一个空的 Cookie（默认排在最后，并自动选中）。 */
    fun addCookie() {
        val list = cookies.value.toMutableList()
        list.add("")
        cookies.value = list
        persistCookies()
        setSelectedIndex(list.lastIndex)
        ProxyServer.setCookieList(list)
    }

    /** 删除指定 Cookie（至少保留默认一项）。 */
    fun removeCookie(i: Int) {
        if (cookies.value.size <= 1) return
        val list = cookies.value.toMutableList()
        list.removeAt(i)
        cookies.value = list
        persistCookies()
        if (selectedIndex.value >= list.size) selectedIndex.value = list.lastIndex
        prefs.edit().putInt("doubao_cookie_selected", selectedIndex.value).apply()
        ProxyServer.setCookieList(list)
    }
    val chatModel = MutableStateFlow(prefs.getString("chat_model", "hy3-preview") ?: "hy3-preview")
    val port = MutableStateFlow(prefs.getInt("port", 8800))
    val streamMode = MutableStateFlow(prefs.getBoolean("stream_mode", true))
    val serverRunning = LocalGatewayServiceLifecycle.running
    val serverError = LocalGatewayServiceLifecycle.lastError
    val logs = LogStore.lines
    val chatMessages = MutableStateFlow<List<ChatMessageItem>>(emptyList())
    val imageSize = MutableStateFlow(prefs.getString("image_size", "9:16") ?: "9:16")
    val chatInput = MutableStateFlow("")

    fun setChatModel(m: String) {
        chatModel.value = m
        prefs.edit().putString("chat_model", m).apply()
    }

    fun setPort(p: Int) {
        port.value = p
        prefs.edit().putInt("port", p).apply()
    }

    fun setStreamMode(v: Boolean) {
        streamMode.value = v
        ProxyServer.streamMode = v
        prefs.edit().putBoolean("stream_mode", v).apply()
    }

    fun setImageSize(s: String) {
        imageSize.value = s
        ProxyServer.defaultImageSize = s
        prefs.edit().putString("image_size", s).apply()
    }

    fun startServer() {
        val app = getApplication<Application>()
        val intent = Intent(app, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START_EXPLICIT
            putExtra(ProxyService.EXTRA_PORT, port.value)
            putExtra(ProxyService.EXTRA_COOKIES, ArrayList(cookies.value))
            putExtra(ProxyService.EXTRA_STREAM, streamMode.value)
        }
        ProxyServer.setWebSessions(webSessions.value)
        ProxyServer.streamMode = streamMode.value
        ProxyServer.defaultImageSize = imageSize.value
        ProxyServer.setBearerToken(localBearerToken.value)
        LocalGatewayServiceLifecycle.startRequested()
        try {
            ContextCompat.startForegroundService(app, intent)
            LogStore.i("Proxy", "▶ 已请求启动本机网关: http://127.0.0.1:${port.value}/v1/chat/completions")
        } catch (failure: Throwable) {
            LocalGatewayServiceLifecycle.startFailed(failure.message.orEmpty())
            LogStore.e("Proxy", "无法发起本地网关服务: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    private suspend fun ensureLocalGatewayRunning() {
        if (ProxyServer.isRunning()) return
        gatewayStartMutex.withLock {
            if (ProxyServer.isRunning()) return@withLock
            LogStore.i("App", "本地调用需要 8800 网关，正在自动启动")
            startServer()
            var attempts = 0
            while (!ProxyServer.isRunning() && attempts < 50) {
                delay(100)
                attempts++
            }
            if (!ProxyServer.isRunning()) {
                val reason = LocalGatewayServiceLifecycle.lastError.value
                    .orEmpty().ifBlank { "本地 8800 网关自动启动失败" }
                throw IllegalStateException(reason)
            }
        }
    }

    fun ensureServerStarted() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ensureLocalGatewayRunning() }
                .onFailure {
                    LogStore.e(
                        "Proxy",
                        "自动启动本地网关失败：${it.localizedMessage ?: it.javaClass.simpleName}"
                    )
                }
        }
    }

    fun resetLocalBearerToken() {
        val replacement = localGatewayTokenStore.reset()
        localBearerToken.value = replacement
        ProxyServer.setBearerToken(replacement)
        LogStore.i("Proxy", "本地 Bearer token 已重置，旧 token 立即失效")
    }

    fun stopServer() {
        val app = getApplication<Application>()
        val stopDispatched = app.stopService(Intent(app, ProxyService::class.java))
        if (!stopDispatched) {
            ProxyServer.stop()
            LocalGatewayServiceLifecycle.stopped()
        }
        LogStore.i("Proxy", if (stopDispatched) "■ 已请求停止服务" else "■ 服务未在运行")
    }

    fun sendChat(promptOverride: String? = null, attachments: List<Uri> = emptyList()) {
        val prompt = promptOverride ?: chatInput.value
        if (prompt.isBlank()) return
        chatInput.value = ""
        val attachmentNames = attachments.map(::attachmentName)
        val imageUris = attachments.filter { uri ->
            getApplication<Application>().contentResolver.getType(uri)?.startsWith("image/") == true
        }.map(Uri::toString)
        val visiblePrompt = prompt + attachmentNames.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "\n📎 ", separator = "、").orEmpty()
        chatMessages.value = chatMessages.value + ChatMessageItem("user", visiblePrompt, imageUris)
        chatMessages.value = chatMessages.value + ChatMessageItem("assistant")
        LogStore.i("App", "发送消息: ${prompt.take(80)} (model=${chatModel.value})")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val reply = requestLocalChat(chatModel.value, prompt, attachments) { partial ->
                    chatMessages.value = chatMessages.value.dropLast(1) +
                        ChatMessageItem("assistant", partial.text, partial.images)
                }
                chatMessages.value = chatMessages.value.dropLast(1) + ChatMessageItem("assistant", reply.text, reply.images)
            } catch (e: Exception) {
                LogStore.e("App", "sendChat 异常: ${e.stackTraceToString()}")
                val detail = e.localizedMessage?.takeIf(String::isNotBlank)
                    ?: e.javaClass.simpleName
                chatMessages.value = chatMessages.value.dropLast(1) +
                    ChatMessageItem("assistant", "⚠️ 错误: $detail")
            }
        }
    }

    fun sendRoundtable(prompt: String, requestedModels: List<String>, attachments: List<Uri> = emptyList()) {
        val models = requestedModels.distinct().take(4)
        if (prompt.isBlank() || models.size < 2) return
        chatInput.value = ""
        val attachmentNames = attachments.map(::attachmentName)
        val imageUris = attachments.filter { uri ->
            getApplication<Application>().contentResolver.getType(uri)?.startsWith("image/") == true
        }.map(Uri::toString)
        chatMessages.value = chatMessages.value + ChatMessageItem(
            "user",
            "【圆桌 · ${models.size} 个模型】$prompt" + attachmentNames.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "\n📎 ", separator = "、").orEmpty(),
            imageUris
        )
        viewModelScope.launch(Dispatchers.IO) {
            val independentPrompt = """
                你正在参加多模型圆桌讨论。请独立回答下面的问题，给出明确结论、关键依据和风险；
                不要猜测其他模型的答案，也不要披露隐藏思维过程。

                问题：$prompt
            """.trimIndent()
            val answers = coroutineScope {
                models.map { modelId ->
                    async {
                        modelId to runCatching { requestLocalChat(modelId, independentPrompt, attachments).text }
                            .getOrElse { "⚠️ 调用失败：${it.localizedMessage}" }
                    }
                }.awaitAll()
            }
            val discussion = answers.joinToString("\n\n") { (modelId, answer) ->
                "【${displayModelName(modelId)}】\n$answer"
            }
            chatMessages.value = chatMessages.value + ChatMessageItem("assistant", "圆桌独立意见\n\n$discussion")

            val synthesizer = models.first()
            val synthesisPrompt = buildRoundtableSynthesisPrompt(prompt, answers)
            val summary = runCatching { requestLocalChat(synthesizer, synthesisPrompt).text }
                .getOrElse { "⚠️ 总结失败：${it.localizedMessage}" }
            chatMessages.value = chatMessages.value + ChatMessageItem(
                "assistant", "【圆桌总结 · ${displayModelName(synthesizer)}】\n$summary"
            )
        }
    }

    private fun displayModelName(modelId: String): String =
        (ProviderCatalog.models + providerModels.value).firstOrNull { it.publicModelId == modelId }
            ?.displayName ?: modelId

    private fun attachmentName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty().ifBlank { uri.lastPathSegment ?: "attachment" }
    }

    private fun readAttachment(uri: Uri): RealAttachment {
        val resolver = getApplication<Application>().contentResolver
        val name = attachmentName(uri)
        var mime = resolver.getType(uri) ?: "application/octet-stream"
        var bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取附件：$name")
        require(bytes.size <= 15 * 1024 * 1024) { "附件超过 15MB：$name" }
        if (mime.startsWith("image/")) {
            bytes = compressForVision(bytes, name)
            mime = "image/jpeg"
            LogStore.i("Attachment", "$name 已压缩为 ${bytes.size / 1024}KB 后发送")
        }
        val text = if (mime.startsWith("text/")) bytes.toString(Charsets.UTF_8) else null
        return RealAttachment(name, mime, "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}", text)
    }

    private fun compressForVision(source: ByteArray, name: String): ByteArray {
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size)
            ?: throw IllegalArgumentException("无法解析图片：$name")
        val maxSide = 1280
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(
            decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true
        ) else decoded
        val flattened = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        val output = ByteArrayOutputStream()
        var quality = 82
        do {
            output.reset()
            flattened.compress(Bitmap.CompressFormat.JPEG, quality, output)
            quality -= 10
        } while (output.size() > 650 * 1024 && quality >= 52)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        flattened.recycle()
        require(output.size() <= 900 * 1024) { "图片压缩后仍过大：$name" }
        return output.toByteArray()
    }

    private suspend fun requestLocalChat(
        modelId: String,
        prompt: String,
        attachmentUris: List<Uri> = emptyList(),
        onProgress: ((LocalChatReply) -> Unit)? = null
    ): LocalChatReply {
        ensureLocalGatewayRunning()
        val endpoint = "http://127.0.0.1:${port.value}/v1/chat/completions"
        val attachments = attachmentUris.map(::readAttachment)
        val content: Any = if (attachments.isEmpty()) prompt else JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            attachments.forEach { item ->
                when {
                    item.mime.startsWith("image/") -> put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", item.dataUrl))
                    })
                    item.text != null -> put(JSONObject().put("type", "text").put(
                        "text", "附件 ${item.name} 的完整内容：\n${item.text}"
                    ))
                    else -> put(JSONObject().apply {
                        put("type", "file")
                        put("file", JSONObject().put("filename", item.name).put("file_data", item.dataUrl))
                    })
                }
            }
        }
        val body = JSONObject().apply {
            put("model", modelId)
            put("stream", onProgress != null)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        }.toString()
        val req = Request.Builder().url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${localBearerToken.value}")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val respBody = resp.body?.string().orEmpty()
                val err = runCatching { JSONObject(respBody).optString("error", respBody.take(300)) }
                    .getOrDefault(respBody.take(300))
                throw IllegalStateException("HTTP ${resp.code}: $err")
            }
            if (onProgress != null) {
                val text = StringBuilder()
                val images = mutableListOf<String>()
                val source = resp.body?.source() ?: return@use LocalChatReply("")
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ") || line == "data: [DONE]") continue
                    val delta = runCatching {
                        JSONObject(line.removePrefix("data: ")).getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                    }.getOrNull() ?: continue
                    text.append(delta.optString("content"))
                    delta.optJSONArray("images")?.let { array ->
                        (0 until array.length()).mapTo(images) { array.optString(it) }
                    }
                    onProgress(LocalChatReply(text.toString(), images.filter(String::isNotBlank).distinct()))
                }
                return@use LocalChatReply(text.toString(), images.filter(String::isNotBlank).distinct())
            }
            val respBody = resp.body?.string().orEmpty()
            val message = JSONObject(respBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val images = message.optJSONArray("images")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
            }.orEmpty()
            LocalChatReply(message.optString("content"), images)
        }
    }

    /** 生图测试：按 imageModel 调用本地 /v1/images/generations。 */
    fun generateImage(prompt: String, attachmentUris: List<Uri> = emptyList()) {
        if (prompt.isBlank() && attachmentUris.isEmpty()) return
        chatInput.value = ""
        val images = attachmentUris.mapNotNull { uri ->
            runCatching { 
                readAttachment(uri) 
            }.onFailure { 
                LogStore.e("App", "生图读取附件失败 uri=$uri: ${it.message}")
            }.getOrNull()
        }.filter { it.mime.startsWith("image/") }
        LogStore.i("App", "生图附件: attachmentUris=${attachmentUris.size}, 读取成功=${images.size}, dataUrl前20=${images.firstOrNull()?.dataUrl?.take(20)}")
        chatMessages.value = chatMessages.value + ChatMessageItem(
            "user", 
            "【生图】$prompt",
            images = images.map { it.dataUrl }
        )
        val allImageModels = ProviderCatalog.imageModels.map { "${it.publicModelId}(${it.providerType.name})" }.joinToString(", ")
        android.util.Log.i("ImageGen", "生图请求: ${prompt.take(80)} (model=${imageModel.value}, size=${imageSize.value}, 参考图=${images.size})")
        android.util.Log.i("ImageGen", "当前可用生图模型: $allImageModels")

        val endpoint = "http://127.0.0.1:${port.value}/v1/images/generations"
        android.util.Log.i("ImageGen", "准备构造生图请求 body, imageModel.value=${imageModel.value}")
        val body = JSONObject().apply {
            put("model", imageModel.value)
            put("prompt", prompt)
            put("size", imageSize.value)
            put("n", 1)
            if (images.isNotEmpty()) {
                val arr = JSONArray()
                for (img in images) {
                    val b64 = img.dataUrl.substringAfter("base64,", "")
                    val decoded = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
                    val bounds = decoded?.let {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                        opts
                    }
                    arr.put(JSONObject().apply {
                        put("name", img.name)
                        put("mime", img.mime)
                        put("data", b64)
                        put("width", bounds?.outWidth ?: 0)
                        put("height", bounds?.outHeight ?: 0)
                    })
                }
                put("image_data", arr)
            }
        }.toString()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureLocalGatewayRunning()
                val req = Request.Builder().url(endpoint)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer ${localBearerToken.value}")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val respBody = resp.body!!.string()
                    if (!resp.isSuccessful) {
                        val err = try {
                            val error = JSONObject(respBody).opt("error")
                            when (error) {
                                is JSONObject -> error.optString("message")
                                    .ifBlank { error.optString("code") }
                                    .ifBlank { error.toString().take(300) }
                                is String -> error
                                else -> respBody.take(300)
                            }
                        } catch (_: Exception) {
                            respBody.take(300)
                        }
                        LogStore.e("App", "生图失败 HTTP ${resp.code}: $err")
                        chatMessages.value = chatMessages.value + ChatMessageItem("assistant", "⚠️ 生图错误: $err")
                        return@use
                    }
                    val data = JSONObject(respBody).optJSONArray("data") ?: JSONArray()
                    val urls = (0 until data.length()).mapNotNull { i ->
                        data.optJSONObject(i)?.optString("url", "")
                    }.filter { it.isNotBlank() }
                    if (urls.isEmpty()) {
                        chatMessages.value = chatMessages.value + ChatMessageItem("assistant", "⚠️ 生图未返回图片")
                    } else {
                        chatMessages.value = chatMessages.value + ChatMessageItem("assistant", images = urls)
                    }
                }
            } catch (e: Exception) {
                LogStore.e("App", "generateImage 异常: ${e.stackTraceToString()}")
                chatMessages.value = chatMessages.value + ChatMessageItem("assistant", "⚠️ 生图错误: ${e.localizedMessage}")
            }
        }
    }

    /** 视频生成：调用本地 /v1/videos/generations（自定义 Web WebView 驱动）。 */
    fun generateVideo(prompt: String, attachmentUris: List<Uri> = emptyList()) {
        if (prompt.isBlank() && attachmentUris.isEmpty()) return
        chatInput.value = ""
        val images = attachmentUris.mapNotNull(::readAttachment).filter { it.mime.startsWith("image/") }
        chatMessages.value = chatMessages.value + ChatMessageItem(
            "user",
            "【生成视频】$prompt",
            images = images.map { it.dataUrl }
        )
        LogStore.i("App", "视频请求: ${prompt.take(80)} (model=${videoModel.value}, 参考图=${images.size})")

        val endpoint = "http://127.0.0.1:${port.value}/v1/videos/generations"
        val body = JSONObject().apply {
            put("model", videoModel.value)
            put("prompt", prompt)
            put("n", 1)
            if (images.isNotEmpty()) {
                val arr = JSONArray()
                for (img in images) {
                    val b64 = img.dataUrl.substringAfter("base64,", "")
                    val decoded = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
                    val bounds = decoded?.let {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                        opts
                    }
                    arr.put(JSONObject().apply {
                        put("name", img.name)
                        put("mime", img.mime)
                        put("data", b64)
                        put("width", bounds?.outWidth ?: 0)
                        put("height", bounds?.outHeight ?: 0)
                    })
                }
                put("image_data", arr)
            }
        }.toString()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureLocalGatewayRunning()
                val req = Request.Builder().url(endpoint)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer ${localBearerToken.value}")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val respBody = resp.body!!.string()
                    if (!resp.isSuccessful) {
                        val err = try {
                            val error = JSONObject(respBody).opt("error")
                            when (error) {
                                is JSONObject -> error.optString("message")
                                    .ifBlank { error.optString("code") }
                                    .ifBlank { error.toString().take(300) }
                                is String -> error
                                else -> respBody.take(300)
                            }
                        } catch (_: Exception) {
                            respBody.take(300)
                        }
                        LogStore.e("App", "视频生成失败 HTTP ${resp.code}: $err")
                        chatMessages.value = chatMessages.value + ChatMessageItem("assistant", "⚠️ 视频生成错误: $err")
                        return@use
                    }
                    val data = JSONObject(respBody).optJSONArray("data") ?: JSONArray()
                    val urls = (0 until data.length()).mapNotNull { i ->
                        data.optJSONObject(i)?.optString("url", "")
                    }.filter { it.isNotBlank() }
                    if (urls.isEmpty()) {
                        chatMessages.value = chatMessages.value + ChatMessageItem("assistant", "⚠️ 视频生成未返回结果")
                    } else {
                        chatMessages.value = chatMessages.value + ChatMessageItem("assistant", videos = urls)
                    }
                }
            } catch (e: Exception) {
                LogStore.e("App", "generateVideo 异常: ${e.stackTraceToString()}")
                chatMessages.value = chatMessages.value + ChatMessageItem("assistant", "⚠️ 视频生成错误: ${e.localizedMessage}")
            }
        }
    }
}
