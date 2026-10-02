package com.wenwei.deepseekproxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.VideoView
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.wenwei.deepseekproxy.proxy.ApiProviderConfig
import com.wenwei.deepseekproxy.proxy.ProviderCapability
import com.wenwei.deepseekproxy.proxy.LogStore
import com.wenwei.deepseekproxy.proxy.LocalGatewayTokenStore
import com.wenwei.deepseekproxy.proxy.LocalGatewaySecurity
import com.wenwei.deepseekproxy.proxy.SavedWebSession
import com.wenwei.deepseekproxy.proxy.WebProviderType
import com.wenwei.deepseekproxy.proxy.WebCookieSnapshot
import com.wenwei.deepseekproxy.proxy.CustomWebProviderConfig
import com.wenwei.deepseekproxy.proxy.BrowserRelayActivation
import com.wenwei.deepseekproxy.proxy.isKnownCustomWebImagePage
import com.wenwei.deepseekproxy.proxy.customWebImageModelId
import com.wenwei.deepseekproxy.workbuddy.WorkBuddyCredentialStore
import com.wenwei.deepseekproxy.workbuddy.WorkBuddyStatusCard
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

private class WebSessionJsBridge(
    private val callback: (String) -> Unit,
    private val onSaveImageUrl: (String) -> Unit = {},
) {
    @JavascriptInterface
    fun onChatGptSession(sessionJson: String) {
        Handler(Looper.getMainLooper()).post { callback(sessionJson) }
    }

    @JavascriptInterface
    fun onSaveImage(imageUrl: String) {
        if (imageUrl.isBlank()) return
        Handler(Looper.getMainLooper()).post { onSaveImageUrl(imageUrl) }
    }
}
private class TouchWebView(context: Context) : WebView(context) {
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onTouchEvent(event)
    }
}

private fun webLoginUserAgent(
    context: Context,
    provider: WebProviderType,
    compatibilityMode: Boolean = false
): String =
    if (provider == WebProviderType.DOUBAO_WEB) {
        // Keep Doubao on the real WebView identity. Its login session is bound to the
        // browser fingerprint and is invalidated when a spoofed UA is used.
        WebSettings.getDefaultUserAgent(context)
    } else if (provider == WebProviderType.CHATGPT_WEB &&
        (compatibilityMode || isHuaweiOrHonorDevice())
    ) {
        // Some Huawei WebView builds expose a vendor/embedded UA that ChatGPT rejects
        // even though the same engine can render the page. Keep this scoped to GPT.
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    } else {
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
    }

private fun isHuaweiOrHonorDevice(): Boolean =
    listOf(Build.MANUFACTURER, Build.BRAND).any { value ->
        value.contains("huawei", ignoreCase = true) ||
            value.contains("honor", ignoreCase = true)
    }

private fun openWebLoginUrlInSameView(view: WebView, targetUrl: String): Boolean {
    if (targetUrl.startsWith("http://", ignoreCase = true) ||
        targetUrl.startsWith("https://", ignoreCase = true)
    ) {
        view.loadUrl(targetUrl)
        return true
    }
    if (!targetUrl.startsWith("intent://", ignoreCase = true)) return false
    return runCatching {
        val intent = android.content.Intent.parseUri(targetUrl, android.content.Intent.URI_INTENT_SCHEME)
        val fallback = intent.getStringExtra("browser_fallback_url")
        when {
            !fallback.isNullOrBlank() -> view.loadUrl(fallback)
            intent.data != null -> view.loadUrl(intent.data.toString())
            else -> return@runCatching false
        }
        true
    }.getOrDefault(false)
}

private fun clearProviderCookies(provider: WebProviderType) {
    val manager = CookieManager.getInstance()
    provider.cookieOrigins.forEach { origin ->
        manager.getCookie(origin).orEmpty().split(';').map { it.substringBefore('=').trim() }
            .filter(String::isNotBlank).distinct().forEach { name ->
                manager.setCookie(origin, "$name=; Max-Age=0; Path=/")
                if (provider == WebProviderType.DOUBAO_WEB) {
                    manager.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=.doubao.com")
                }
            }
    }
    manager.flush()
}

private fun decodeJavascriptString(raw: String?): String {
    if (raw == null || raw == "null") return ""
    return runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault(raw)
}

private fun geminiLoginState(view: WebView, callback: (signedIn: Boolean, message: String) -> Unit) {
    view.evaluateJavascript(
        """
        (() => {
          const href=location.href||'';
          const body=(document.body?.innerText||'').trim();
          const visible=e=>!!(e&&e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden');
          const controls=[...document.querySelectorAll('a,button')].filter(visible);
          const signIn=controls.some(e=>/^(sign in|log in|登录|登入)$/i.test((e.innerText||e.textContent||e.getAttribute('aria-label')||'').trim()));
          const account=controls.some(e=>/google account|google 账号|google 帳戶/i.test((e.getAttribute('aria-label')||e.title||'').trim()));
          const editor=!!document.querySelector('rich-textarea .ql-editor[contenteditable="true"],.ql-editor[contenteditable="true"],div[contenteditable="true"][role="textbox"],textarea');
          const error=/出了点问题|something went wrong|稍后再试|try again later/i.test(body);
          const onGemini=/^https:\/\/gemini\.google\.com\/app(?:[/?#]|$)/i.test(href);
          return JSON.stringify({onGemini,signIn,account,editor,error,href});
        })()
        """.trimIndent()
    ) { raw ->
        val state = runCatching { JSONObject(decodeJavascriptString(raw)) }.getOrElse { JSONObject() }
        val signedIn = state.optBoolean("onGemini") &&
            !state.optBoolean("signIn") &&
            !state.optBoolean("error") &&
            (state.optBoolean("account") || state.optBoolean("editor"))
        val message = when {
            !state.optBoolean("onGemini") -> "请先进入 Gemini 主站完成登录"
            state.optBoolean("error") -> "Gemini 页面当前显示错误，请刷新并确认正常后再保存"
            state.optBoolean("signIn") -> "Gemini 主站仍显示登录按钮，当前 Google 登录未对 Gemini 生效"
            !signedIn -> "尚未确认 Gemini 登录状态，请等待页面加载完成后重试"
            else -> "Gemini 主站登录验证通过"
        }
        callback(signedIn, message)
    }
}

private fun restoreOriginCookiesThenLoad(
    view: WebView,
    cookieHeadersByOrigin: Map<String, String>,
    targetUrl: String,
    onComplete: () -> Unit,
) {
    val writes = cookieHeadersByOrigin.flatMap { (origin, header) ->
        header.split(';').map(String::trim)
            .filter { it.contains('=') }
            .map { origin to it }
    }
    if (writes.isEmpty()) {
        onComplete()
        return
    }
    val manager = CookieManager.getInstance()
    var remaining = writes.size
    writes.forEach { (origin, cookie) ->
        manager.setCookie(origin, cookie) {
            remaining -= 1
            if (remaining == 0) {
                manager.flush()
                view.loadUrl(targetUrl)
                onComplete()
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: ProxyViewModel by viewModels()

    // 网页端文件选择：onShowFileChooser 拉起系统选择器后，回传结果给 WebView
    @Volatile
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        if (uri != null) {
            cb?.onReceiveValue(arrayOf(uri))
        } else {
            cb?.onReceiveValue(null)
        }
    }

    fun openWebFileChooser(callback: android.webkit.ValueCallback<Array<Uri>>) {
        android.util.Log.i("NativeWeb", "onShowFileChooser callback received")
        pendingFileCallback = callback
        if (com.wenwei.deepseekproxy.proxy.NativeWebFileChooserBridge.consume(this, callback)) {
            pendingFileCallback = null
            return
        }
        runCatching { fileChooserLauncher.launch("image/*") }
            .onFailure {
                pendingFileCallback = null
                callback.onReceiveValue(null)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkBuddyCredentialStore.attach(this)
        vm.ensureBailianLoaded()
        android.util.Log.i("MainActivity", "=== onCreate called, app starting ===")
        setContent { App(vm) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: ProxyViewModel) {
    val cookiesFlow by vm.cookies.collectAsState()
    val selectedIdx by vm.selectedIndex.collectAsState()
    val modelFlow by vm.chatModel.collectAsState()
    val portFlow by vm.port.collectAsState()
    val running by vm.serverRunning.collectAsState()
    val streamFlow by vm.streamMode.collectAsState()
    val messages by vm.chatMessages.collectAsState()
    val logs by vm.logs.collectAsState()
    val webSessions by vm.webSessions.collectAsState()
    val webSessionStatus by vm.webSessionStatus.collectAsState()
    val customWebProviders by vm.customWebProviders.collectAsState()
    val apiProviders by vm.apiProviders.collectAsState()
    val apiProviderStatus by vm.apiProviderStatus.collectAsState()

    var cookieText by remember { mutableStateOf(cookiesFlow.getOrNull(selectedIdx) ?: "") }
    LaunchedEffect(selectedIdx) { cookieText = cookiesFlow.getOrNull(selectedIdx) ?: "" }

    var model by remember { mutableStateOf(modelFlow) }
    var port by remember { mutableStateOf(portFlow.toString()) }
    var input by remember { mutableStateOf("") }
    var streamMode by remember { mutableStateOf(streamFlow) }
    var tab by remember { mutableStateOf(0) }
    
    // 监控当前 tab
    LaunchedEffect(tab) {
        android.util.Log.i("CurrentTab", "当前 tab=$tab (0=中转, 1=接口, 2=日志, 3=浏览器)")
    }
    
    var showCookieDialog by remember { mutableStateOf(false) }
    var browserRequestedModelId by remember { mutableStateOf(modelFlow) }
    val externalBrowserRequest by BrowserRelayActivation.requests.collectAsState()

    LaunchedEffect(externalBrowserRequest.sequence) {
        externalBrowserRequest.modelId.takeIf(String::isNotBlank)?.let { requested ->
            browserRequestedModelId = requested
            LogStore.i("Browser", "外部 API 自动激活模型 $requested")
        }
    }

    if (showCookieDialog) {
        CookiePickerDialog(
            cookies = cookiesFlow,
            selectedIndex = selectedIdx,
            onSelect = { vm.setSelectedIndex(it); showCookieDialog = false },
            onAdd = { vm.addCookie() },
            onDelete = { vm.removeCookie(it) },
            onDismiss = { showCookieDialog = false }
        )
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    actions = {
                        TabRow(
                            selectedTabIndex = tab,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Tab(selected = tab == 0, onClick = { 
                                android.util.Log.i("TabSwitch", "切换到 tab=0 (中转)")
                                tab = 0 
                            }) { Text("中转") }
                            Tab(selected = tab == 1, onClick = { 
                                android.util.Log.i("TabSwitch", "切换到 tab=1 (接口)")
                                tab = 1 
                            }) { Text("接口") }
                            Tab(selected = tab == 2, onClick = { 
                                android.util.Log.i("TabSwitch", "切换到 tab=2 (日志)")
                                tab = 2 
                            }) { Text("日志") }
                            Tab(selected = tab == 3, onClick = { 
                                android.util.Log.i("TabSwitch", "切换到 tab=3 (浏览器)")
                                tab = 3 
                            }) { Text("浏览器") }
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // Keep the WebView composed, visible and laid out behind the opaque
                // relay/log screens. Making the AndroidView layer fully transparent can
                // throttle WebView JavaScript on some devices and delay relay -> web sends.
                BrowserScreen(
                    visible = tab == 3,
                    requestedProvider = com.wenwei.deepseekproxy.proxy.ProviderCatalog
                        .find(browserRequestedModelId)?.providerType,
                    requestedModelId = browserRequestedModelId,
                    sessions = webSessions,
                    customProviders = customWebProviders,
                    sessionStatus = webSessionStatus,
                    onSessionSaved = { provider, name, cookie, authToken, cookiesByOrigin, extraHeaders ->
                        vm.saveWebSession(provider, name, cookie, authToken, cookiesByOrigin, extraHeaders)
                        if (provider == WebProviderType.DOUBAO_WEB) cookieText = cookie
                    },
                    onSessionDeleted = vm::deleteWebSession,
                    onSessionTest = vm::testWebSession,
                    onSessionStatusChanged = vm::updateWebSessionStatus,
                    onCustomProviderSaved = vm::saveCustomWebProvider,
                    onCustomProviderDeleted = vm::deleteCustomWebProvider,
                    onRelayReady = vm::ensureServerStarted,
                    onBailianCaptured = { json -> vm.applyBailianConfig(json) },
                    modifier = Modifier.fillMaxSize()
                )
                if (tab == 0) {
                    Surface(Modifier.fillMaxSize()) {
                        ProxyScreen(
                            vm, cookieText, model, port, running, streamMode, messages, input,
                            cookieLabel = vm.cookieLabel(selectedIdx),
                            onCookieChange = {
                                cookieText = it
                                vm.updateSelectedCookie(it)
                            },
                            onOpenCookieDialog = { showCookieDialog = true },
                            onModelChange = {
                                model = it
                                vm.setChatModel(it)
                                browserRequestedModelId = it
                            },
                            onBrowserModelRequested = { browserRequestedModelId = it },
                            onPortChange = { port = it },
                            onInputChange = { input = it },
                            onStreamModeChange = {
                                streamMode = it
                                vm.setStreamMode(it)
                            },
                            onStart = {
                                vm.updateSelectedCookie(cookieText.trim())
                                vm.setChatModel(model)
                                vm.startServer()
                            },
                            onStop = { vm.stopServer() },
                            onSavePort = { vm.setPort(port.toIntOrNull() ?: 8800) },
                            onSend = { payload, attachments ->
                                vm.sendChat(payload, attachments)
                                input = ""
                            },
                            onRoundtable = { payload, models, attachments ->
                                vm.sendRoundtable(payload, models, attachments)
                                input = ""
                            },
                            onGenerate = { payload, attachments ->
                                vm.generateImage(payload, attachments)
                                input = ""
                            },
                            onGenerateVideo = { payload, attachments, dur, res, rat, fps, aud, wmk, template ->
                                vm.generateVideo(
                                    prompt = payload,
                                    attachmentUris = attachments,
                                    duration = dur,
                                    resolution = res,
                                    ratio = rat,
                                    fps = fps,
                                    audio = aud,
                                    watermark = wmk,
                                    template = template
                                )
                                input = ""
                            },
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                } else if (tab == 1) {
                    Surface(Modifier.fillMaxSize()) {
                        ApiProviderScreen(
                            providers = apiProviders,
                            status = apiProviderStatus,
                            onSave = vm::saveApiProvider,
                            onDelete = vm::deleteApiProvider,
                            onTest = { vm.testApiProvider(it) },
                            onSync = { vm.testApiProvider(it, syncModels = true) },
                            onSpeedTestAll = vm::testAllApiProviders,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else if (tab == 2) {
                    Surface(Modifier.fillMaxSize()) {
                        LogScreen(
                            logs = logs,
                            onClear = { LogStore.clear() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiProviderScreen(
    providers: List<ApiProviderConfig>,
    status: Map<String, String>,
    onSave: (ApiProviderConfig) -> Unit,
    onDelete: (String) -> Unit,
    onTest: (ApiProviderConfig) -> Unit,
    onSync: (ApiProviderConfig) -> Unit,
    onSpeedTestAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf<ApiProviderConfig?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("API 接口管理", style = MaterialTheme.typography.titleLarge)
                Text("支持 OpenAI Compatible 聊天与生图接口", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSpeedTestAll) { Text("全部测速") }
            Button(onClick = { creating = true }) { Text("＋ 添加") }
        }
        if (providers.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text("还没有自定义接口。点击“添加”，填写接口地址和 API Key，应用会自动发现模型。", Modifier.padding(16.dp))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(providers, key = { it.id }) { provider ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(provider.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text(if (provider.enabled) "已启用" else "已停用", color = MaterialTheme.colorScheme.primary)
                            }
                            Text(provider.normalizedBaseUrl(), style = MaterialTheme.typography.bodySmall)
                            Text("聊天模型 ${provider.chatModels.size} 个 · 生图模型 ${provider.imageModels.size} 个 · 视频模型 ${provider.videoModels.size} 个")
                            status[provider.id]?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { editing = provider }) { Text("编辑") }
                                TextButton(onClick = { onTest(provider) }) { Text("测试连接") }
                                TextButton(onClick = { onSync(provider) }) { Text("重新解析模型") }
                                TextButton(onClick = { onDelete(provider.id) }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        ApiProviderEditorDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = {
                onSave(it)
                creating = false
                editing = null
            }
        )
    }
}

@Composable
private fun ApiProviderEditorDialog(
    initial: ApiProviderConfig?,
    onDismiss: () -> Unit,
    onSave: (ApiProviderConfig) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember(initial?.id) { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var apiKey by remember(initial?.id) { mutableStateOf(initial?.apiKey.orEmpty()) }
    // 打开编辑框时，把聊天模型里明显是 video 的模型自动挪到视频框（与「重新解析模型」的
    // 挪移规则一致），避免旧的视频模型残留聊天框、用户手动搬的麻烦。
    val initialChat = remember(initial?.id) {
        val chat = initial?.chatModels.orEmpty()
        val video = initial?.videoModels.orEmpty()
        fun isVideoModel(id: String): Boolean =
            id.contains("video", ignoreCase = true) ||
                Regex("(^|[-_./])(sora|kling|wanx|veo|t2v|v2v|text[-_]?to[-_]?video|animate|cogvideo)([-_./]|$)", RegexOption.IGNORE_CASE).containsMatchIn(id)
        chat.filterNot(::isVideoModel) to (video + chat.filter(::isVideoModel)).distinct()
    }
    var chatModels by remember(initial?.id) { mutableStateOf(initialChat.first.joinToString("\n")) }
    var imageModels by remember(initial?.id) { mutableStateOf(initial?.imageModels?.joinToString("\n").orEmpty()) }
    var videoModels by remember(initial?.id) { mutableStateOf(initialChat.second.joinToString("\n")) }
    var enabled by remember(initial?.id) { mutableStateOf(initial?.enabled ?: true) }

    fun parseModels(value: String): List<String> = value.split(',', '\n', ';')
        .map(String::trim).filter(String::isNotBlank).distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加 API 接口" else "编辑 API 接口") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("接口名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    baseUrl, { baseUrl = it }, label = { Text("接口地址") },
                    placeholder = { Text("可填 …/v1 或 …/v1/chat/completions") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    apiKey, { apiKey = it }, label = { Text("API Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = chatModels, onValueChange = { chatModels = it },
                    label = { Text("聊天模型（可手动补充）") }, placeholder = { Text("保存后自动读取 /v1/models") },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    imageModels, { imageModels = it },
                    label = { Text("生图模型（逗号或换行分隔）") },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    videoModels, { videoModels = it },
                    label = { Text("视频模型（逗号或换行分隔）") },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    enabled = chatModels.isNotBlank(),
                    onClick = {
                        imageModels = (parseModels(imageModels) + parseModels(chatModels))
                            .distinct().joinToString("\n")
                    }
                ) { Text("上游未声明生图能力？把聊天模型加入生图候选") }
                TextButton(
                    enabled = chatModels.isNotBlank(),
                    onClick = {
                        videoModels = (parseModels(videoModels) + parseModels(chatModels))
                            .distinct().joinToString("\n")
                    }
                ) { Text("上游未声明视频能力？把聊天模型加入视频候选") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用接口", Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                Text("自动同步只增加、不删除已有模型。幻城等未声明能力的接口，可手动填写生图模型或使用上方按钮。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && baseUrl.startsWith("http"),
                onClick = {
                    onSave(ApiProviderConfig(
                        id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name.trim(), baseUrl = baseUrl.trim(), apiKey = apiKey.trim(),
                        chatModels = parseModels(chatModels), imageModels = parseModels(imageModels), videoModels = parseModels(videoModels), enabled = enabled
                    ))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProxyScreen(
    vm: ProxyViewModel,
    cookie: String,
    model: String,
    port: String,
    running: Boolean,
    streamMode: Boolean,
    messages: List<ChatMessageItem>,
    input: String,
    cookieLabel: String,
    onCookieChange: (String) -> Unit,
    onOpenCookieDialog: () -> Unit,
    onModelChange: (String) -> Unit,
    onBrowserModelRequested: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onStreamModeChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSavePort: () -> Unit,
    onSend: (String, List<Uri>) -> Unit,
    onRoundtable: (String, List<String>, List<Uri>) -> Unit,
    onGenerate: (String, List<Uri>) -> Unit,
    onGenerateVideo: (String, List<Uri>, String, String, String, String, String, String, String?) -> Unit = { payload, uris, _, _, _, _, _, _, _ -> onGenerate(payload, uris) },
    modifier: Modifier = Modifier
) {
    android.util.Log.i("ProxyScreen", "=== ProxyScreen recomposing, model=$model, input=$input ===")
    val portFlow by vm.port.collectAsState()
    val context = LocalContext.current

    val runtimeModels by vm.providerModels.collectAsState()
    val apiProviderConfigs by vm.apiProviders.collectAsState()
    val imageModelFlow by vm.imageModel.collectAsState()
    val videoModelFlow by vm.videoModel.collectAsState()
    // 视频参数（设置级联菜单）：Auto=不传由上游默认；自定义=手填字符串；音效/无水印=开关
    var videoDuration by remember { mutableStateOf("Auto") }
    var videoResolution by remember { mutableStateOf("Auto") }
    var videoRatio by remember { mutableStateOf("Auto") }
    var videoFps by remember { mutableStateOf("Auto") }
    var videoAudio by remember { mutableStateOf("关") }
    var videoWatermark by remember { mutableStateOf("开") }
    // NanoAI 玩法模板（style_name，null=不使用）；仅 NanoAI 模型在设置菜单里出现「模板」项
    var videoTemplate by remember { mutableStateOf<String?>(null) }
    // 过滤「冗余纯别名」：buildPlainModelAliases 生成的 id 就是 upstream，
    // 若存在带接口前缀版本（X--upstream），纯别名只是兜底，UI 里隐藏避免成对。
    fun <T> dedupeProviderModels(models: List<T>): List<T> {
        val prefixed = models.mapNotNull { (it as? com.wenwei.deepseekproxy.proxy.ProviderModel)?.publicModelId }
            .filter { it.contains("--") }
            .map { it.substringAfter("--") }
            .toSet()
        return models.filter {
            val m = it as? com.wenwei.deepseekproxy.proxy.ProviderModel ?: return@filter true
            val id = m.publicModelId
            // 纯名（不含 --）且存在带前缀别名版本 → 隐藏纯名，保留带前缀版
            val isPlain = !id.contains("--")
            !(isPlain && prefixed.any { p -> p.equals(id, ignoreCase = true) })
        }
    }
    val chatModelItems = dedupeProviderModels(
        (com.wenwei.deepseekproxy.proxy.ProviderCatalog.models.filter { it.enabled } + runtimeModels)
            .filter {
                it.enabled &&
                    com.wenwei.deepseekproxy.proxy.ProviderCapability.CHAT in it.capabilities
            }.distinctBy { it.publicModelId.lowercase() }
    )
    val modelOptions = chatModelItems.map { it.publicModelId to it.displayName }
    fun providerKey(item: com.wenwei.deepseekproxy.proxy.ProviderModel): String = when (item.providerType) {
        WebProviderType.CUSTOM_WEB -> "custom:${item.accountId}"
        WebProviderType.OPENAI_COMPATIBLE -> "api:${item.accountId ?: "unknown"}"
        else -> "type:${item.providerType.name}"
    }
    fun providerLabel(items: List<com.wenwei.deepseekproxy.proxy.ProviderModel>): String {
        val item = items.first()
        return when (item.providerType) {
            WebProviderType.CUSTOM_WEB -> item.displayName.removeSuffix(" 生图").removeSuffix("视频")
            WebProviderType.OPENAI_COMPATIBLE -> when (item.accountId) {
                "auto" -> "API 自动最快"
                else -> apiProviderConfigs.firstOrNull { it.id == item.accountId }?.name ?: "API 接口"
            }
            else -> item.providerType.displayName
        }
    }
    val providerOptions = chatModelItems.groupBy(::providerKey).map { (key, items) ->
        key to providerLabel(items)
    }
    // 隐藏的内置供应商：与「浏览器」tab 共享同一份 hidden_web_providers 集合（key = WebProviderType.name）
    val hiddenPrefs = remember { context.getSharedPreferences("hidden_web_providers", Context.MODE_PRIVATE) }
    var hiddenProviders by remember {
        mutableStateOf(hiddenPrefs.getStringSet("hidden", emptySet())?.toSet().orEmpty())
    }
    fun persistHidden(set: Set<String>) {
        hiddenPrefs.edit().putStringSet("hidden", set).apply()
    }
    fun isHiddenProviderKey(key: String): Boolean = when {
        key.startsWith("type:") -> key.removePrefix("type:") in hiddenProviders
        key.startsWith("api:") -> key in hiddenProviders
        key.startsWith("custom:") -> key in hiddenProviders
        else -> false
    }
    // 恢复列表：type:（内置 Web）/ api:（接口供应商）/ custom:（自定义 Web）都支持
    val hiddenWebProviders = remember(hiddenProviders) {
        hiddenProviders.mapNotNull { h ->
            when {
                h.startsWith("api:") -> Triple(h, "api", h.removePrefix("api:"))
                h.startsWith("custom:") -> Triple(h, "custom", h.removePrefix("custom:"))
                else -> Triple(h, "type", h)
            }
        }
    }
    val visibleProviderOptions = providerOptions.filter { !isHiddenProviderKey(it.first) }
    var pendingHideRelayProvider by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var hiddenMenu by remember { mutableStateOf(false) }
    val selectedProviderKey = chatModelItems.firstOrNull { it.publicModelId == model }
        ?.let(::providerKey) ?: visibleProviderOptions.firstOrNull()?.first
    val selectedProviderLabel = providerOptions.firstOrNull { it.first == selectedProviderKey }?.second.orEmpty()
    var modelExpanded by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    val localBearerToken by vm.localBearerToken.collectAsState()
    var revealLocalToken by remember { mutableStateOf(false) }
    var confirmTokenReset by remember { mutableStateOf(false) }
    var imageMode by remember { mutableStateOf(false) }
    var videoMode by remember { mutableStateOf(false) }
    
    // 监控 imageMode 变化
    LaunchedEffect(imageMode) {
        android.util.Log.i("ImageModeMonitor", "imageMode state changed to: $imageMode")
    }
    // 监控 videoMode 变化
    LaunchedEffect(videoMode) {
        android.util.Log.i("VideoModeMonitor", "videoMode state changed to: $videoMode")
    }
    
    var roundtableMode by remember { mutableStateOf(false) }
    var roundtableDialog by remember { mutableStateOf(false) }
    var roundtableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingAttachments by remember { mutableStateOf<List<Uri>>(emptyList()) }

    fun attachmentName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment ?: "附件" }

    val attachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        pendingAttachments = (pendingAttachments + uris).distinct()
    }

    LaunchedEffect(modelOptions, model) {
        if (modelOptions.none { it.first == model }) {
            modelOptions.firstOrNull()?.first?.let(onModelChange)
        }
        roundtableModels = roundtableModels.filter { selected -> modelOptions.any { it.first == selected } }
        if (roundtableModels.size < 2) {
            roundtableModels = (listOf(model) + modelOptions.map { it.first }).distinct().take(2)
        }
    }

    if (roundtableDialog) {
        AlertDialog(
            onDismissRequest = { roundtableDialog = false },
            title = { Text("圆桌模型（2–4 个）") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(chatModelItems, key = { it.publicModelId }) { item ->
                        val checked = item.publicModelId in roundtableModels
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                roundtableModels = if (checked) {
                                    roundtableModels - item.publicModelId
                                } else if (roundtableModels.size < 4) {
                                    roundtableModels + item.publicModelId
                                } else roundtableModels
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column {
                                Text(item.displayName)
                                Text(item.providerType.displayName, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { roundtableDialog = false; roundtableMode = true },
                    enabled = roundtableModels.size >= 2
                ) { Text("启用圆桌") }
            },
            dismissButton = {
                TextButton(onClick = { roundtableMode = false; roundtableDialog = false }) { Text("切回单聊") }
            }
        )
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        LogStore.i("App", "通知权限授予=$granted")
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun onStartClick() {
        ensureNotificationPermission()
        onStart()
    }

    if (confirmTokenReset) {
        AlertDialog(
            onDismissRequest = { confirmTokenReset = false },
            title = { Text("重置本地 Bearer token？") },
            text = { Text("旧 token 会立即失效，JRead 等已配置客户端需要改用新 token。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetLocalBearerToken()
                    confirmTokenReset = false
                }) { Text("确认重置") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTokenReset = false }) { Text("取消") }
            },
        )
    }

    Column(modifier.imePadding()) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (running) "● $portFlow" else "○ $portFlow",
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !imageMode && !videoMode,
                    onClick = {
                        imageMode = false
                        videoMode = false
                        onBrowserModelRequested(model)
                    },
                    label = { Text("聊天") }
                )
                Spacer(Modifier.width(6.dp))
                // 生图 chip
                run {
                    android.util.Log.i("ChipRender", "生图 FilterChip 正在渲染, imageMode=$imageMode, videoMode=$videoMode")
                    FilterChip(
                        selected = imageMode,
                        onClick = {
                            android.util.Log.i("ImageModeSwitch", "【生图】chip 被点击！")
                            android.util.Log.i("ImageModeSwitch", "点击前 imageMode=$imageMode, videoMode=$videoMode")
                            imageMode = true
                            videoMode = false
                            android.util.Log.i("ImageModeSwitch", "点击后 imageMode=$imageMode, videoMode=$videoMode")
                            onBrowserModelRequested(imageModelFlow)
                        },
                        label = { Text("生图") }
                    )
                }
                Spacer(Modifier.width(6.dp))
                // 视频 chip
                run {
                    FilterChip(
                        selected = videoMode,
                        onClick = {
                            android.util.Log.i("VideoModeSwitch", "【视频】chip 被点击！")
                            android.util.Log.i("VideoModeSwitch", "点击前 imageMode=$imageMode, videoMode=$videoMode")
                            imageMode = false
                            videoMode = true
                            android.util.Log.i("VideoModeSwitch", "点击后 imageMode=$imageMode, videoMode=$videoMode")
                            onBrowserModelRequested(imageModelFlow)
                        },
                        label = { Text("视频") }
                    )
                }
                android.util.Log.i("VideoModeChip", "渲染完成 imageMode=$imageMode, videoMode=$videoMode")
                TextButton(
                    onClick = { settingsExpanded = !settingsExpanded },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text(if (settingsExpanded) "收起" else "设置") }
            }
            if (settingsExpanded) {
                HorizontalDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { onPortChange(it.filter(Char::isDigit)) },
                            label = { Text("端口") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.width(104.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onSavePort) { Text("保存") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onStartClick() }) { Text(if (running) "重启" else "启动") }
                        if (running) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = onStop) { Text("停止") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = cookie,
                            onValueChange = onCookieChange,
                            label = { Text(cookieLabel) },
                            placeholder = { Text("豆包 Cookie") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onOpenCookieDialog) { Text("管理") }
                    }
                    OutlinedTextField(
                        value = localBearerToken,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("本地 Bearer token") },
                        visualTransformation = if (revealLocalToken) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { revealLocalToken = !revealLocalToken }) {
                            Text(if (revealLocalToken) "隐藏" else "显示")
                        }
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MultiWeb Bearer token", localBearerToken))
                            Toast.makeText(context, "token 已复制", Toast.LENGTH_SHORT).show()
                        }) { Text("复制") }
                        TextButton(onClick = { confirmTokenReset = true }) { Text("重置") }
                        TextButton(onClick = { ensureNotificationPermission() }) { Text("通知权限") }
                    }
                    Text(
                        "网关只监听 127.0.0.1；发送聊天、生图或打开网页登录中转时会自动启动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (!imageMode && !videoMode) {
        Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor().fillMaxWidth().heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "供应商",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                selectedProviderLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(if (providerExpanded) "▴" else "▾", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    ExposedDropdownMenu(providerExpanded, { providerExpanded = false }) {
                        visibleProviderOptions.forEach { (optionKey, providerLabel) ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            chatModelItems.firstOrNull { providerKey(it) == optionKey }?.publicModelId?.let(onModelChange)
                                            providerExpanded = false
                                        },
                                        onLongClick = {
                                            providerExpanded = false
                                            pendingHideRelayProvider = Triple(optionKey, providerLabel, "chat")
                                        }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(providerLabel)
                            }
                        }
                        if (hiddenWebProviders.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("已隐藏的供应商（${hiddenWebProviders.size}）") },
                                onClick = { providerExpanded = false; hiddenMenu = true }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = streamMode,
                    onClick = { onStreamModeChange(!streamMode) },
                    label = { Text("流式") }
                )
            }
        } else if (imageMode) {
            val imageModels = dedupeProviderModels(com.wenwei.deepseekproxy.proxy.ProviderCatalog.imageModels)
                .distinctBy { it.publicModelId.lowercase() }
            LaunchedEffect(imageModels) {
                android.util.Log.i("ImageModels", "所有可用生图模型(${imageModels.size}):")
                imageModels.forEach { model ->
                    android.util.Log.i("ImageModels", "  ${model.publicModelId} -> providerKey=${providerKey(model)}, type=${model.providerType.name}, accountId=${model.accountId}")
                }
            }
            val imageProviderOptions = imageModels.groupBy(::providerKey).map { (key, items) ->
                key to providerLabel(items)
            }
            val visibleImageProviderOptions = imageProviderOptions.filter { !isHiddenProviderKey(it.first) }
            val selectedImageProviderKey = imageModels.firstOrNull { it.publicModelId == imageModelFlow }
                ?.let(::providerKey) ?: visibleImageProviderOptions.firstOrNull()?.first
            val selectedImageProviderLabel = imageProviderOptions
                .firstOrNull { it.first == selectedImageProviderKey }?.second.orEmpty()
            val selectedProviderImageModels = imageModels.filter { providerKey(it) == selectedImageProviderKey }
            val imageSizeFlow by vm.imageSize.collectAsState()
            var imageProviderExpanded by remember { mutableStateOf(false) }
            var imageModelExpanded by remember { mutableStateOf(false) }
            var sizeExpanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 生图供应商框
                ExposedDropdownMenuBox(
                    expanded = imageProviderExpanded,
                    onExpandedChange = { imageProviderExpanded = it },
                    modifier = Modifier.weight(1.2f)
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor().fillMaxWidth().heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectedImageProviderLabel.isEmpty()) {
                                Text("生图供应商", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    selectedImageProviderLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(if (imageProviderExpanded) "▴" else "▾", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    ExposedDropdownMenu(imageProviderExpanded, { imageProviderExpanded = false }) {
                        visibleImageProviderOptions.forEach { (optionKey, label) ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            val foundModel = imageModels.firstOrNull { providerKey(it) == optionKey }
                                            imageModels.filter { providerKey(it) == optionKey }.forEach {
                                                android.util.Log.i("ImageSwitch", "    匹配到: ${it.publicModelId}")
                                            }
                                            foundModel?.publicModelId?.let {
                                                vm.setImageModel(it)
                                                onBrowserModelRequested(it)
                                            } ?: android.util.Log.e("ImageSwitch", "  未找到匹配模型，imageModel 保持不变")
                                            imageProviderExpanded = false
                                        },
                                        onLongClick = {
                                            imageProviderExpanded = false
                                            pendingHideRelayProvider = Triple(optionKey, label, "image")
                                        }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(label)
                            }
                        }
                        if (hiddenWebProviders.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("已隐藏的供应商（${hiddenWebProviders.size}）") },
                                onClick = { imageProviderExpanded = false; hiddenMenu = true }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 生图模型框
                ExposedDropdownMenuBox(
                    expanded = imageModelExpanded,
                    onExpandedChange = { imageModelExpanded = it },
                    modifier = Modifier.weight(1.5f)
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor().fillMaxWidth().heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currentImageModel = imageModels.firstOrNull { it.publicModelId == imageModelFlow }?.displayName ?: imageModelFlow
                            if (currentImageModel.isEmpty() || imageModels.firstOrNull { it.publicModelId == imageModelFlow } == null) {
                                Text("模型", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    currentImageModel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(if (imageModelExpanded) "▴" else "▾", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    ExposedDropdownMenu(imageModelExpanded, { imageModelExpanded = false }) {
                        selectedProviderImageModels.forEach { item -> DropdownMenuItem(
                            text = { Text(item.groupedLabel) },
                            onClick = {
                                vm.setImageModel(item.publicModelId)
                                onBrowserModelRequested(item.publicModelId)
                                imageModelExpanded = false
                            }
                        ) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 设置框
                ExposedDropdownMenuBox(
                    expanded = sizeExpanded,
                    onExpandedChange = { sizeExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor().fillMaxWidth().heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                listOf("9:16 (竖屏)" to "9:16", "3:4 (竖版)" to "3:4", "1:1 (方形)" to "1:1", "16:9 (横屏)" to "16:9", "4:3 (横版)" to "4:3", "2:5 (长竖屏)" to "2:5", "5:2 (长横屏)" to "5:2").firstOrNull { (_, size) -> size == imageSizeFlow }?.first ?: imageSizeFlow,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(if (sizeExpanded) "▴" else "▾", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    ExposedDropdownMenu(sizeExpanded, { sizeExpanded = false }) {
                        listOf("9:16 (竖屏)" to "9:16", "3:4 (竖版)" to "3:4", "1:1 (方形)" to "1:1", "16:9 (横屏)" to "16:9", "4:3 (横版)" to "4:3", "2:5 (长竖屏)" to "2:5", "5:2 (长横屏)" to "5:2").forEach { (label, id) -> DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { vm.setImageSize(id); sizeExpanded = false }
                        ) }
                    }
                }
            }
        }
        // 视频模式：照生图做「视频供应商 + 视频模型」下拉。
        else {
            // 视频 tab：WebView 驱动的 CUSTOM_WEB + 接口 tab 的 OPENAI_COMPATIBLE 都支持视频
            val videoModels = dedupeProviderModels(com.wenwei.deepseekproxy.proxy.ProviderCatalog.videoModels)
                .filter {
                    it.providerType == WebProviderType.CUSTOM_WEB ||
                        it.providerType == WebProviderType.OPENAI_COMPATIBLE ||
                        it.providerType == WebProviderType.DOUBAO_WEB ||
                        it.providerType == WebProviderType.NANOAI_VIDEO
                }
                .distinctBy { it.publicModelId.lowercase() }
            // 视频 tab 复用生图模型源，显示时把「生图」替换为「视频」
            fun videoLabel(s: String): String = s.replace("生图", "").replace("视频", "")
            val videoProviderOptions = videoModels.groupBy(::providerKey).map { (key, items) ->
                // 供应商名去尾部「视频」（如「美团 视频」→「美团」），模型名不受影响
                key to videoLabel(providerLabel(items)).replace(Regex("\\s*视频$"), "")
            }
            val visibleVideoProviderOptions = videoProviderOptions.filter { !isHiddenProviderKey(it.first) }
            val selectedVideoProviderKey = videoModels.firstOrNull { it.publicModelId == videoModelFlow }
                ?.let(::providerKey) ?: visibleVideoProviderOptions.firstOrNull()?.first
            val selectedVideoProviderLabel = videoProviderOptions
                .firstOrNull { it.first == selectedVideoProviderKey }?.second.orEmpty()
            val selectedProviderVideoModels = videoModels.filter { providerKey(it) == selectedVideoProviderKey }
            var videoProviderExpanded by remember { mutableStateOf(false) }
            var videoModelExpanded by remember { mutableStateOf(false) }
            var videoSettingsExpanded by remember { mutableStateOf(false) }
            var videoSettingsCat by remember { mutableStateOf<String?>(null) }
            var customParamDialog by remember { mutableStateOf<String?>(null) }
            var customParamText by remember { mutableStateOf("") }
            // 当前选中的视频模型是否 NanoAI（是则在设置菜单里提供「模板」）
            val isNanoAiVideo = videoModels.firstOrNull { it.publicModelId == videoModelFlow }?.providerType == WebProviderType.NANOAI_VIDEO
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 视频供应商框
                ExposedDropdownMenuBox(
                    expanded = videoProviderExpanded,
                    onExpandedChange = { videoProviderExpanded = it },
                    modifier = Modifier.weight(1.2f)
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor().fillMaxWidth().heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectedVideoProviderLabel.isEmpty()) {
                                Text("视频供应商", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    selectedVideoProviderLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(if (videoProviderExpanded) "▴" else "▾", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    ExposedDropdownMenu(videoProviderExpanded, { videoProviderExpanded = false }) {
                        visibleVideoProviderOptions.forEach { (optionKey, label) ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            videoModels.firstOrNull { providerKey(it) == optionKey }?.publicModelId?.let {
                                                vm.setVideoModel(it)
                                                onBrowserModelRequested(it)
                                            }
                                            videoProviderExpanded = false
                                        },
                                        onLongClick = {
                                            videoProviderExpanded = false
                                            pendingHideRelayProvider = Triple(optionKey, label, "video")
                                        }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(label)
                            }
                        }
                        if (hiddenWebProviders.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("已隐藏的供应商（${hiddenWebProviders.size}）") },
                                onClick = { videoProviderExpanded = false; hiddenMenu = true }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 视频模型框
                ExposedDropdownMenuBox(
                    expanded = videoModelExpanded,
                    onExpandedChange = { videoModelExpanded = it },
                    modifier = Modifier.weight(1.5f)
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor().fillMaxWidth().heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currentVideoModel = videoLabel(videoModels.firstOrNull { it.publicModelId == videoModelFlow }?.displayName ?: videoModelFlow)
                            if (currentVideoModel.isEmpty() || videoModels.firstOrNull { it.publicModelId == videoModelFlow } == null) {
                                Text("模型", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    currentVideoModel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(if (videoModelExpanded) "▴" else "▾", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    ExposedDropdownMenu(videoModelExpanded, { videoModelExpanded = false }) {
                        selectedProviderVideoModels.forEach { item -> DropdownMenuItem(
                            text = { Text(videoLabel(item.groupedLabel)) },
                            onClick = {
                                vm.setVideoModel(item.publicModelId)
                                onBrowserModelRequested(item.publicModelId)
                                videoModelExpanded = false
                            }
                        ) }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 设置框（静态）
                ExposedDropdownMenuBox(
                    expanded = videoSettingsExpanded,
                    onExpandedChange = { videoSettingsExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor().fillMaxWidth().heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "设置",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(if (videoSettingsExpanded) "▴" else "▾", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    ExposedDropdownMenu(videoSettingsExpanded, {
                        videoSettingsExpanded = false
                        videoSettingsCat = null
                    }) {
                        // 第一级：参数名列表（点→进入二级）
                        if (videoSettingsCat == null) {
                            val settingCats = if (isNanoAiVideo)
                                listOf("模板", "时长", "清晰度", "比例", "帧数", "音效", "无水印")
                            else listOf("时长", "清晰度", "比例", "帧数", "音效", "无水印")
                            settingCats.forEach { cat ->
                                val current = when (cat) {
                                    "模板" -> videoTemplate?.let { com.wenwei.deepseekproxy.proxy.NanoAiVideoClient.findTemplate(it)?.styleName } ?: "不使用"
                                    "时长" -> videoDuration; "清晰度" -> videoResolution; "比例" -> videoRatio
                                    "帧数" -> videoFps; "音效" -> videoAudio; else -> videoWatermark
                                }
                                DropdownMenuItem(
                                    text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(cat, modifier = Modifier.weight(1f))
                                        Text(current, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(" ▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } },
                                    onClick = { videoSettingsCat = cat }
                                )
                            }
                        } else if (videoSettingsCat == "模板") {
                            // 模板二级列表：不使用 + 按类别分组的 27 个模板
                            DropdownMenuItem(
                                text = { Text("◂ 模板", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { videoSettingsCat = null }
                            )
                            DropdownMenuItem(
                                text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("不使用模板", color = if (videoTemplate == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    if (videoTemplate == null) Text(" ✓", color = MaterialTheme.colorScheme.primary)
                                } },
                                onClick = { videoTemplate = null; videoSettingsCat = null; videoSettingsExpanded = false }
                            )
                            com.wenwei.deepseekproxy.proxy.NanoAiVideoClient.TEMPLATES.groupBy { it.category }.forEach { (cat, items) ->
                                DropdownMenuItem(
                                    text = { Text(cat, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = {},
                                    enabled = false
                                )
                                items.forEach { tpl ->
                                    val selected = videoTemplate == tpl.styleName
                                    DropdownMenuItem(
                                        text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                tpl.styleName + if (tpl.modelType == "k_grok") "（可灵）" else "",
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (selected) Text(" ✓", color = MaterialTheme.colorScheme.primary)
                                        } },
                                        onClick = {
                                            videoTemplate = tpl.styleName
                                            videoSettingsCat = null
                                            videoSettingsExpanded = false
                                        }
                                    )
                                }
                            }
                        } else {
                            // 第二级：该参数的选项
                            val (options, current) = when (videoSettingsCat) {
                                "时长" -> listOf("Auto", "5s", "10s", "自定义") to videoDuration
                                "清晰度" -> listOf("Auto", "480p", "720p", "1080p", "自定义") to videoResolution
                                "比例" -> listOf("Auto", "9:16", "3:4", "1:1", "自定义") to videoRatio
                                "帧数" -> listOf("Auto", "30FPS", "60FPS", "自定义") to videoFps
                                "音效" -> listOf("关", "开") to videoAudio
                                else -> listOf("开", "关") to videoWatermark
                            }
                            DropdownMenuItem(
                                text = { Text("◂ ${videoSettingsCat}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { videoSettingsCat = null }
                            )
                            options.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(opt, if (opt == current) Modifier else Modifier, color = if (opt == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        if (opt == current) Text(" ✓", color = MaterialTheme.colorScheme.primary)
                                    } },
                                    onClick = {
                                        if (opt == "自定义") {
                                            customParamText = ""
                                            customParamDialog = videoSettingsCat
                                            videoSettingsExpanded = false
                                            videoSettingsCat = null
                                        } else {
                                            when (videoSettingsCat) {
                                                "时长" -> videoDuration = opt
                                                "清晰度" -> videoResolution = opt
                                                "比例" -> videoRatio = opt
                                                "帧数" -> videoFps = opt
                                                "音效" -> videoAudio = opt
                                                else -> videoWatermark = opt
                                            }
                                            videoSettingsCat = null
                                            videoSettingsExpanded = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            customParamDialog?.let { paramName ->
                AlertDialog(
                    onDismissRequest = { customParamDialog = null },
                    title = { Text("自定义$paramName") },
                    text = {
                        Column {
                            Text(
                                when (paramName) {
                                    "时长" -> "示例：5s、8s"
                                    "清晰度" -> "示例：720p、1080p"
                                    "比例" -> "示例：9:16、16:9"
                                    "帧数" -> "示例：30FPS、24FPS"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = customParamText,
                                onValueChange = { customParamText = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val v = customParamText.trim()
                            if (v.isNotEmpty()) {
                                when (paramName) {
                                    "时长" -> videoDuration = v
                                    "清晰度" -> videoResolution = v
                                    "比例" -> videoRatio = v
                                    "帧数" -> videoFps = v
                                }
                            }
                            customParamDialog = null
                        }) { Text("确定") }
                    },
                    dismissButton = {
                        TextButton(onClick = { customParamDialog = null }) { Text("取消") }
                    }
                )
            }
        }
        pendingHideRelayProvider?.let { (hideKey, hideLabel, hideMode) ->
            AlertDialog(
                onDismissRequest = { pendingHideRelayProvider = null },
                title = { Text("隐藏供应商") },
                text = { Text("确定要隐藏「$hideLabel」吗？隐藏后可在「已隐藏的供应商」中恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        // type: 前缀存纯名（兼容旧数据）；api:/custom: 存完整 key
                        val storedKey = if (hideKey.startsWith("type:")) hideKey.removePrefix("type:") else hideKey
                        val newSet = hiddenProviders + storedKey
                        hiddenProviders = newSet
                        persistHidden(newSet)
                        // 隐藏的是当前选中供应商时，回退到第一个可见供应商，避免「幽灵选中」
                        when (hideMode) {
                            "image" -> {
                                val imgModels = com.wenwei.deepseekproxy.proxy.ProviderCatalog.imageModels
                                val curKey = imgModels.firstOrNull { it.publicModelId == imageModelFlow }?.let(::providerKey)
                                if (curKey == hideKey) {
                                    imgModels.firstOrNull { providerKey(it) != hideKey && !isHiddenProviderKey(providerKey(it)) }?.publicModelId?.let {
                                        vm.setImageModel(it)
                                        onBrowserModelRequested(it)
                                    }
                                }
                            }
                            "video" -> {
                                val vidModels = com.wenwei.deepseekproxy.proxy.ProviderCatalog.videoModels
                                val curKey = vidModels.firstOrNull { it.publicModelId == videoModelFlow }?.let(::providerKey)
                                if (curKey == hideKey) {
                                    vidModels.firstOrNull { providerKey(it) != hideKey && !isHiddenProviderKey(providerKey(it)) }?.publicModelId?.let {
                                        vm.setVideoModel(it)
                                        onBrowserModelRequested(it)
                                    }
                                }
                            }
                            else -> if (selectedProviderKey == hideKey) {
                                providerOptions.firstOrNull { it.first != hideKey && !isHiddenProviderKey(it.first) }?.first?.let { k ->
                                    chatModelItems.firstOrNull { providerKey(it) == k }?.publicModelId?.let(onModelChange)
                                }
                            }
                        }
                        pendingHideRelayProvider = null
                    }) { Text("隐藏") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingHideRelayProvider = null }) { Text("取消") }
                }
            )
        }
        if (hiddenMenu) {
            AlertDialog(
                onDismissRequest = { hiddenMenu = false },
                title = { Text("已隐藏的供应商") },
                text = {
                    Column {
                        if (hiddenWebProviders.isEmpty()) {
                            Text("暂无隐藏的供应商", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        hiddenWebProviders.forEach { (storedKey, kind, id) ->
                            val display = when (kind) {
                                "api" -> apiProviderConfigs.firstOrNull { it.id == id }?.name ?: id
                                "custom" -> com.wenwei.deepseekproxy.proxy.CustomWebProviderRegistry.find(id)?.name ?: id
                                else -> WebProviderType.entries.firstOrNull { it.name == id }?.displayName ?: id
                            }
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val newSet = hiddenProviders - storedKey
                                    hiddenProviders = newSet
                                    persistHidden(newSet)
                                    hiddenMenu = false
                                }
                            ) { Text(display) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { hiddenMenu = false }) { Text("关闭") } }
            )
        }
        Spacer(Modifier.height(3.dp))

        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(messages) { msg ->
                val isUser = msg.role == "user"
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.widthIn(max = 300.dp).padding(4.dp)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            if (msg.text == "__VIDEO_GENERATING__") {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("视频生成中…")
                                }
                            } else if (msg.text.isNotBlank()) {
                                SelectionContainer {
                                    Text(msg.text)
                                }
                                if (msg.images.isNotEmpty()) Spacer(Modifier.height(6.dp))
                            }
                            msg.images.forEach { url ->
                                Spacer(Modifier.height(6.dp))
                                UrlImage(url, Modifier.fillMaxWidth())
                            }
                            msg.videos.forEach { url ->
                                Spacer(Modifier.height(6.dp))
                                UrlVideo(url, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (pendingAttachments.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pendingAttachments.forEach { uri ->
                            InputChip(
                                selected = false,
                                onClick = { pendingAttachments = pendingAttachments - uri },
                                label = { Text(attachmentName(uri), maxLines = 1) },
                                trailingIcon = { Text("×") }
                            )
                        }
                    }
                }
                fun doSend() {
                    android.util.Log.i("SendButton", "doSend() 被调用，imageMode=$imageMode, videoMode=$videoMode, input长度=${input.length}, 附件数=${pendingAttachments.size}")
                    val payload = input.trim().ifBlank {
                        if (pendingAttachments.isNotEmpty()) "请分析附件" else ""
                    }
                    android.util.Log.i("SendButton", "payload='$payload'")
                    val hasImage = pendingAttachments.any { uri ->
                        context.contentResolver.getType(uri)?.startsWith("image/") == true
                    }
                    if (!imageMode && !videoMode && hasImage) {
                        val selectedModels = if (roundtableMode) {
                            roundtableModels.mapNotNull { id -> chatModelItems.firstOrNull { it.publicModelId == id } }
                        } else listOfNotNull(chatModelItems.firstOrNull { it.publicModelId == model })
                        val unsupported = selectedModels.filterNot { it.supportsImageInput }
                        if (unsupported.isNotEmpty()) {
                            Toast.makeText(
                                context,
                                "${unsupported.joinToString { it.displayName }} 不支持图片，请改选 Vision/VL/Gemini/GPT-4o 模型",
                                Toast.LENGTH_LONG
                            ).show()
                            return
                        }
                    }
                    val currentVideo = com.wenwei.deepseekproxy.proxy.ProviderCatalog.find(model)?.let { ProviderCapability.VIDEO_GENERATION in it.capabilities } == true
                    if (currentVideo) {
                        videoMode = true
                        imageMode = false
                    }
                    android.util.Log.i("SendButton", "准备调用回调: imageMode=$imageMode, videoMode=$videoMode, roundtableMode=$roundtableMode")
                    if (videoMode) {
                        android.util.Log.i("SendButton", "调用 onGenerateVideo")
                        if (!videoTemplate.isNullOrBlank() && pendingAttachments.isEmpty()) {
                            // 模板效果以照片为画面来源（图生视频），纯文字模板大概率无效
                            Toast.makeText(context, "模板效果需要配一张真实照片（图生视频），纯文字可能无效果", Toast.LENGTH_LONG).show()
                        }
                        onGenerateVideo(
                            payload,
                            pendingAttachments,
                            videoDuration,
                            videoResolution,
                            videoRatio,
                            videoFps,
                            videoAudio,
                            videoWatermark,
                            videoTemplate
                        )
                    } else if (imageMode) {
                        android.util.Log.i("SendButton", "调用 onGenerate")
                        onGenerate(payload, pendingAttachments)
                    } else if (roundtableMode && roundtableModels.size >= 2) {
                        android.util.Log.i("SendButton", "调用 onRoundtable")
                        onRoundtable(payload, roundtableModels, pendingAttachments)
                    } else {
                        android.util.Log.i("SendButton", "调用 onSend")
                        onSend(payload, pendingAttachments)
                    }
                    pendingAttachments = emptyList()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { attachmentLauncher.launch(arrayOf("image/*", "text/*", "application/pdf")) }
                    ) { Text("＋", fontSize = 22.sp) }
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { doSend() }),
                        decorationBox = { inner ->
                            Box {
                                if (input.isBlank()) Text(
                                    when {
                                        imageMode -> "描述画面"
                                        videoMode -> "描述视频"
                                        else -> "输入消息"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                inner()
                            }
                        }
                    )
                    if (!imageMode && !videoMode) {
                        val hasPendingImage = pendingAttachments.any { uri ->
                            context.contentResolver.getType(uri)?.startsWith("image/") == true
                        }
                        val currentModelItem = chatModelItems.firstOrNull { it.publicModelId == model }
                        val providerModels = chatModelItems.filter {
                            providerKey(it) == selectedProviderKey && (!hasPendingImage || it.supportsImageInput)
                        }
                        ExposedDropdownMenuBox(
                            expanded = modelExpanded,
                            onExpandedChange = { modelExpanded = it },
                            modifier = Modifier.widthIn(min = 110.dp, max = 180.dp)
                        ) {
                            TextButton(
                                onClick = { modelExpanded = true },
                                modifier = Modifier.menuAnchor()
                            ) {
                                Text(
                                    if (roundtableMode) "圆桌 · ${roundtableModels.size}模型"
                                    else "单聊 · ${modelOptions.firstOrNull { it.first == model }?.second ?: model}" +
                                        if (hasPendingImage && currentModelItem?.supportsImageInput != true) "（不支持图片）" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(" ▾")
                            }
                            ExposedDropdownMenu(modelExpanded, { modelExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("圆桌模式 · 选择 2–4 个模型") },
                                    onClick = { modelExpanded = false; roundtableDialog = true }
                                )
                                HorizontalDivider()
                                if (providerModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("当前接口没有可识图模型") },
                                        enabled = false,
                                        onClick = {}
                                    )
                                }
                                providerModels.forEach { item -> DropdownMenuItem(
                                    text = { Text("单聊 · ${item.displayName}") },
                                    onClick = {
                                        roundtableMode = false
                                        onModelChange(item.publicModelId)
                                        modelExpanded = false
                                    }
                                ) }
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    FilledIconButton(
                        onClick = { 
                            android.util.Log.i("SendButtonClick", "Button clicked! imageMode=$imageMode, videoMode=$videoMode, input='$input', attachments=${pendingAttachments.size}")
                            doSend() 
                        },
                        enabled = input.isNotBlank() || pendingAttachments.isNotEmpty()
                    ) { Text(
                        when {
                            imageMode -> "🎨"
                            videoMode -> "🎬"
                            else -> "↑"
                        },
                        style = MaterialTheme.typography.titleMedium
                    ) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookiePickerDialog(
    cookies: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择豆包 Cookie") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
            ) {
                itemsIndexed(cookies) { i, c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(i) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (i == 0) "默认Cookie" else "Cookie ${i + 1}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (c.isBlank()) "（空）" else (c.take(40) + if (c.length > 40) "…" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (i == selectedIndex) {
                            Text("当前", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (cookies.size > 1) {
                            TextButton(onClick = { onDelete(i) }) { Text("删除") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAdd) { Text("新增Cookie") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun UrlImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewRef = remember { mutableStateOf<ImageView?>(null) }
    val scope = rememberCoroutineScope()
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setOnLongClickListener {
                    scope.launch {
                        val bytes = fetchImageBytes(context, url)
                        if (bytes != null) {
                            val ok = saveImageToGallery(context, bytes)
                            Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "图片获取失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
            }.also { viewRef.value = it }
        },
        modifier = modifier
    )
    LaunchedEffect(url) {
        val iv = viewRef.value ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val bytes = fetchImageBytes(context, url) ?: return@withContext
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) withContext(Dispatchers.Main) {
                iv.setImageBitmap(bmp)
                iv.requestLayout()
            }
        }
    }
}

/** 消息内视频播放器：Media3 ExoPlayer（带软解回退，解决 VideoView 硬解黑屏）。 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun UrlVideo(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localFile by remember { mutableStateOf<File?>(null) }

    // ExoPlayer 实例，DisposableEffect 释放
    val exoPlayer = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            prepare()
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // 下载缓存（供长按保存 + 播放同一份文件）
    LaunchedEffect(url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            withContext(Dispatchers.IO) {
                try {
                    val dir = File(context.cacheDir, "web-video").apply { mkdirs() }
                    val file = File(dir, "${java.util.UUID.randomUUID()}.mp4")
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            android.util.Log.e("VideoPlayer", "视频下载失败 HTTP=${resp.code} type=${resp.header("Content-Type")}")
                            return@withContext
                        }
                        val bytes = resp.body?.bytes() ?: return@withContext
                        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                        val header = bytes.take(16).joinToString(" ") { "%02x".format(it) }
                        android.util.Log.i("VideoPlayer", "视频下载: type=${resp.header("Content-Type")} bytes=${bytes.size} header=$header sha256=$sha")
                        file.outputStream().use { it.write(bytes) }
                    }
                    localFile = file
                    android.util.Log.i("VideoPlayer", "视频已缓存到 ${file.absolutePath} size=${file.length()}")
                } catch (e: Exception) {
                    android.util.Log.e("VideoPlayer", "视频缓存失败: ${e.message}")
                }
            }
        }
    }

    // ExoPlayer 播放器：支持软解回退，画面正常渲染
    // 长按=保存到相册（PlayerView 会消费触摸，因此手势挂在外层 Box）
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 400.dp)
            .background(Color.Black)
            .clickable { exoPlayer.playWhenReady = !exoPlayer.playWhenReady }
    ) {
        // 经典 PlayerView：只留播放键+进度条（prev/next/快进/快退全部隐藏），
        // 点击呼出/3 秒自动隐藏控制栏，长按保留"保存到相册"。
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                SavePlayerView(ctx).apply {
                    this.player = exoPlayer
                    useController = true
                    setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS)
                    onLongPressSave = {
                        scope.launch {
                            Toast.makeText(context, "正在保存视频…", Toast.LENGTH_SHORT).show()
                            val bytes = localFile?.takeIf { it.exists() }?.readBytes()
                                ?: fetchImageBytes(context, url)
                            if (bytes != null) {
                                val ok = saveVideoToGallery(context, bytes)
                                Toast.makeText(context, if (ok) "视频已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "视频获取失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 自定义 PlayerView：控件条正常使用；长按 900ms 触发保存（自计时，不受控件消费触摸影响）。 */
class SavePlayerView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
) : androidx.media3.ui.PlayerView(context, attrs) {
    var onLongPressSave: (() -> Unit)? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable { onLongPressSave?.invoke() }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN ->
                handler.postDelayed(longPressRunnable, 900L)
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL ->
                handler.removeCallbacks(longPressRunnable)
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }
}

/** 获取图片原始字节（data:image / content:// / http 三种来源，含会话 Cookie 与本地网关鉴权）。 */
private suspend fun fetchImageBytes(context: Context, url: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        if (url.startsWith("data:image/")) {
            val comma = url.indexOf(',')
            if (comma > 0) {
                return@withContext android.util.Base64.decode(
                    url.substring(comma + 1),
                    android.util.Base64.DEFAULT,
                )
            }
            return@withContext null
        }
        if (url.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(url))?.use { input ->
                return@withContext input.readBytes()
            }
            return@withContext null
        }
        if (url.startsWith("file://")) {
            return@withContext runCatching { File(Uri.parse(url).path.orEmpty()).readBytes() }.getOrNull()
        }
        val cookieHeader = runCatching {
            CookieManager.getInstance().getCookie(url).orEmpty()
        }.getOrDefault("")
        val imageReferer = runCatching {
            Uri.parse(url).let { uri ->
                if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) ""
                else "${uri.scheme}://${uri.host}/"
            }
        }.getOrDefault("")
        val client = OkHttpClient()
        val request = Request.Builder().url(url).apply {
            val localPort = context.getSharedPreferences("ds_proxy", Context.MODE_PRIVATE)
                .getInt("port", 8800)
            if (LocalGatewaySecurity.shouldAuthorizeGeneratedImage(url, localPort)) {
                header("Authorization", "Bearer ${LocalGatewayTokenStore(context).getOrCreate()}")
            }
            if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
            header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            if (imageReferer.isNotBlank()) header("Referer", imageReferer)
        }.build()
        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else {
                LogStore.w("Image", "图片加载失败 HTTP ${resp.code}，域名=${request.url.host}")
                null
            }
        }
    } catch (e: Exception) {
        LogStore.w("Image", "图片加载异常：${e.localizedMessage ?: e.javaClass.simpleName}")
        null
    }
}

/**
 * 长按网页图片保存：先 OkHttp 直下（带豆包 Referer 防盗链头）；
 * 直下失败（签名 URL 已失效）则从 WebView 磁盘缓存（Chromium Simple Cache）提取字节。
 */
private suspend fun fetchWebViewImageBytes(context: Context, url: String): ByteArray? = withContext(Dispatchers.IO) {
    if (url.startsWith("data:image/")) {
        val comma = url.indexOf(',')
        if (comma > 0) {
            return@withContext android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
        }
        return@withContext null
    }
    // 1) 直下：带豆包 Referer + Cookie（byteimg 防盗链校验 Referer=doubao.com）
    runCatching {
        val cookieHeader = runCatching {
            CookieManager.getInstance().getCookie(url).orEmpty()
        }.getOrDefault("")
        val client = OkHttpClient()
        val request = Request.Builder().url(url)
            .header("User-Agent", webLoginUserAgent(context, WebProviderType.DOUBAO_WEB, false))
            .header("Referer", "https://www.doubao.com/")
            .apply {
                if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
            }
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }.getOrNull()?.let { bytes ->
        if (isImageBytes(bytes) || isVideoBytes(bytes)) return@withContext bytes
    }
    // 2) 从 WebView 缓存提取
    extractImageFromWebViewCache(context, url)
}

/** 判断字节是否可解码为图片（JPEG/PNG/WebP/GIF 魔数或 BitmapFactory 可解）。 */
private fun isImageBytes(bytes: ByteArray): Boolean {
    if (bytes.size < 16) return false
    val h = bytes
    if (h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte()) return true // JPEG
    if (h[0] == 0x89.toByte() && h[1] == 0x50.toByte() && h[2] == 0x4E.toByte() && h[3] == 0x47.toByte()) return true // PNG
    if (h[0] == 0x52.toByte() && h[1] == 0x49.toByte() && h[2] == 0x46.toByte() && h[3] == 0x46.toByte()) { // RIFF(WebP)
        if (bytes.size > 12 && h[8] == 0x57.toByte() && h[9] == 0x45.toByte() && h[10] == 0x42.toByte() && h[11] == 0x50.toByte()) return true
    }
    return runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.outWidth > 0 && opts.outHeight > 0
    }.getOrDefault(false)
}

private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    outer@ for (i in from..haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

/**
 * 从 WebView Chromium Simple Cache 提取图片字节。
 * 缓存目录：<cacheDir>/WebView/Default/HTTP Cache/Cache_Data/<hash>_0
 * 文件格式：header（含明文 URL） + body（"data:" 前缀后是图片字节）。
 * 策略：按 URL 匹配缓存文件 → 找 JPEG/PNG/WebP 魔数 → 截取到文件尾尝试解码。
 */
private fun extractImageFromWebViewCache(context: Context, url: String): ByteArray? {
    val cacheDir = File(context.cacheDir, "WebView/Default/HTTP Cache/Cache_Data")
    val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith("_0") } ?: return null
    // 优先匹配完整 URL（含 query），退而匹配 path 部分（query 可能被缓存键规范化）
    val urlPath = runCatching { Uri.parse(url).path ?: "" }.getOrDefault("")
    for (f in files) {
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
        if (bytes.size < 64) continue
        // header 是文本，取前 64KB 匹配 URL
        val headerLen = minOf(bytes.size, 65536)
        val header = String(bytes, 0, headerLen, Charsets.ISO_8859_1)
        val matched = header.contains(url) || (urlPath.isNotEmpty() && header.contains(urlPath))
        if (!matched) continue
        val img = extractImageBytes(bytes) ?: continue
        if (isImageBytes(img)) return img
    }
    return null
}

/** 在 Simple Cache body 里定位图片字节：找 JPEG/PNG/WebP 魔数，从魔数截到文件尾。 */
private fun extractImageBytes(bytes: ByteArray): ByteArray? {
    // JPEG: FF D8 FF
    val jpeg = indexOfBytes(bytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
    if (jpeg >= 0) {
        val img = bytes.copyOfRange(jpeg, bytes.size)
        // body 尾部可能有 Chromium 附加数据，先试整段；失败则逐段截断重试
        if (isImageBytes(img)) return img
        for (trim in intArrayOf(16, 64, 256, 1024)) {
            if (bytes.size - jpeg > trim) {
                val t = bytes.copyOfRange(jpeg, bytes.size - trim)
                if (isImageBytes(t)) return t
            }
        }
    }
    // PNG: 89 50 4E 47
    val png = indexOfBytes(bytes, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
    if (png >= 0) {
        val img = bytes.copyOfRange(png, bytes.size)
        if (isImageBytes(img)) return img
    }
    // WebP: RIFF....WEBP
    val riff = indexOfBytes(bytes, byteArrayOf(0x52, 0x49, 0x46, 0x46))
    if (riff >= 0 && bytes.size > riff + 12 &&
        bytes[riff + 8] == 0x57.toByte() && bytes[riff + 9] == 0x45.toByte() && bytes[riff + 10] == 0x42.toByte() && bytes[riff + 11] == 0x50.toByte()
    ) {
        val img = bytes.copyOfRange(riff, bytes.size)
        if (isImageBytes(img)) return img
    }
    return null
}

/** 在 WebView 主线程执行 JS 并取回结果（evaluateJavascript 返回 JSON 编码字符串）。 */
private suspend fun evalJs(webView: WebView, js: String): String? = suspendCancellableCoroutine { cont ->
    webView.post {
        try {
            webView.evaluateJavascript(js) { value ->
                if (cont.isActive) cont.resume(value)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }
}

/**
 * 读取 blob: URL 的图片字节（豆包等站点下载按钮常生成 blob URL）。
 * 通过页面内 fetch + FileReader 转 base64，轮询 window.__dlb64 取回。
 */
private suspend fun fetchBlobBytes(webView: WebView, blobUrl: String): ByteArray? {
    val js = "fetch('$blobUrl').then(r=>r.blob()).then(b=>{const fr=new FileReader();" +
        "fr.onloadend=()=>{window.__dlb64=fr.result.split(',')[1]};fr.readAsDataURL(b)})" +
        ".catch(()=>{window.__dlb64='__ERR__'})"
    evalJs(webView, js)
    for (i in 0 until 120) { // 最多等 12 秒
        kotlinx.coroutines.delay(100)
        val v = evalJs(webView, "window.__dlb64 || ''") ?: continue
        val cleaned = v.trim().removeSurrounding("\"")
        if (cleaned == "__ERR__" || cleaned == "undefined" || cleaned.isEmpty()) continue
        if (cleaned.length > 16) {
            return runCatching {
                android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
            }.getOrNull()?.takeIf { isImageBytes(it) || isVideoBytes(it) }
        }
    }
    return null
}

/** 判断字节是否为视频（MP4/MOV/WebM 等容器魔数）。 */
private fun isVideoBytes(bytes: ByteArray): Boolean {
    if (bytes.size < 16) return false
    // MP4/MOV: 前 4 字节是 box 大小，4-8 字节是 "ftyp"
    if (bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()) return true
    // WebM/MKV: EBML 头 1A 45 DF A3
    if (bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) return true
    return false
}

/** 保存视频到系统相册（MediaStore.Video，Android 10+ 无需存储权限）。 */
private fun saveVideoToGallery(context: Context, bytes: ByteArray): Boolean {
    return try {
        val name = "MultiWeb_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MultiWeb")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    } catch (e: Exception) {
        LogStore.w("Image", "保存视频异常：${e.localizedMessage ?: e.javaClass.simpleName}")
        false
    }
}

/** 保存图片到系统相册（MediaStore，Android 10+ 无需存储权限）。 */
private fun saveImageToGallery(context: Context, bytes: ByteArray): Boolean {
    return try {
        // 统一重编码为 PNG 存，避免 MIME 判断与透明通道丢失
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val pngBytes = out.toByteArray()
        val name = "MultiWeb_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MultiWeb")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            resolver.openOutputStream(uri)?.use { it.write(pngBytes) } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    } catch (e: Exception) {
        LogStore.w("Image", "保存图片异常：${e.localizedMessage ?: e.javaClass.simpleName}")
        false
    }
}

@Composable
fun LogScreen(
    logs: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("运行日志 (${logs.size})", style = MaterialTheme.typography.titleSmall)
            Row {
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("log", logs.joinToString("\n")))
                }) { Text("复制") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onClear) { Text("清空") }
            }
        }
        HorizontalDivider()
        SelectionContainer(Modifier.fillMaxSize().padding(8.dp)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(logs) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * 内置浏览器：默认打开豆包主页，登录后可一键提取 Cookie 并写入设置输入框。
 * Cookie 由系统级 CookieManager 管理，切换标签页/重建 WebView 后登录态仍保留。
 */
private fun isCustomWebViewOnProviderPage(
    config: CustomWebProviderConfig,
    currentUrl: String,
): Boolean {
    fun normalizedHost(value: String): String =
        runCatching { Uri.parse(value).host.orEmpty() }
            .getOrDefault("")
            .lowercase()
            .removePrefix("www.")

    val expectedHost = normalizedHost(config.loginUrl)
    val currentHost = normalizedHost(currentUrl)
    if (expectedHost.isBlank() || currentHost.isBlank()) return false
    return currentHost == expectedHost ||
        currentHost.endsWith(".$expectedHost") ||
        expectedHost.endsWith(".$currentHost")
}

@Composable
fun BrowserScreen(
    visible: Boolean,
    requestedProvider: WebProviderType?,
    requestedModelId: String,
    sessions: List<SavedWebSession>,
    customProviders: List<CustomWebProviderConfig>,
    sessionStatus: Map<String, String>,
    onSessionSaved: (WebProviderType, String, String, String, Map<String, String>, Map<String, String>) -> Unit,
    onSessionDeleted: (String) -> Unit,
    onSessionTest: (SavedWebSession) -> Unit,
    onSessionStatusChanged: (String, String) -> Unit,
    onCustomProviderSaved: (CustomWebProviderConfig) -> Unit,
    onCustomProviderDeleted: (String) -> Unit,
    onRelayReady: () -> Unit,
    onBailianCaptured: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val browserScope = rememberCoroutineScope()
    // 隐藏的内置供应商（可恢复）
    val hiddenPrefs = remember { context.getSharedPreferences("hidden_web_providers", Context.MODE_PRIVATE) }
    var hiddenProviders by remember {
        mutableStateOf(hiddenPrefs.getStringSet("hidden", emptySet())?.toSet().orEmpty())
    }
    fun persistHidden(set: Set<String>) {
        hiddenPrefs.edit().putStringSet("hidden", set).apply()
    }
    val providers = remember(hiddenProviders) {
        WebProviderType.entries.filter { it.supportsWebLogin && it.name !in hiddenProviders }
    }
    val hiddenProviderList = remember(hiddenProviders) {
        WebProviderType.entries.filter { it.supportsWebLogin && it.name in hiddenProviders }
    }
    var hiddenMenu by remember { mutableStateOf(false) }
    // 长按菜单项待确认的删除：内置供应商=隐藏，自定义 Web=真删除
    var pendingHideProvider by remember { mutableStateOf<WebProviderType?>(null) }
    var pendingDeleteCustom by remember { mutableStateOf<CustomWebProviderConfig?>(null) }
    var provider by remember {
        mutableStateOf(
            if (requestedProvider?.supportsWebLogin == true) requestedProvider
            else if (sessions.any { it.providerType == WebProviderType.CHATGPT_WEB })
                WebProviderType.CHATGPT_WEB else WebProviderType.DOUBAO_WEB
        )
    }
    var providerMenu by remember { mutableStateOf(false) }
    var activeCustomId by remember { mutableStateOf<String?>(null) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var accountName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(provider.loginUrl.orEmpty()) }
    var progress by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    var pendingCookie by remember { mutableStateOf("") }
    var pendingAccountName by remember { mutableStateOf("") }
    var compatibilityMode by remember { mutableStateOf(false) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var pendingGeminiValidationSessionId by remember { mutableStateOf<String?>(null) }
    // 「重置当前登录」时置位：下个该供应商页面加载完成后清 localStorage/sessionStorage
    var pendingStorageWipe by remember { mutableStateOf(false) }
    // 会话列表折叠状态（默认收起，省浏览器页纵向空间）
    var sessionsExpanded by remember { mutableStateOf(false) }
    // 用户是否碰过 WebView（碰过就不自动关豆包引导弹窗；页面开始加载时重置）
    var userTouchedWebView by remember { mutableStateOf(false) }
    val capturedAuthorization = remember { AtomicReference("") }
    // DeepSeek x-hif-leim 设备指纹（识图 completion 必需），onPageFinished 时从 localStorage 读取
    val capturedDeepSeekFingerprint = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val webViewInfo = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
                ?: "未检测到系统 WebView"
        } else "系统 WebView"
    }
    val currentSaveCallback by rememberUpdatedState(onSessionSaved)
    val currentSessionStatusCallback by rememberUpdatedState(onSessionStatusChanged)
    val currentRelayReadyCallback by rememberUpdatedState(onRelayReady)
    val activeCustom = customProviders.firstOrNull { it.id == activeCustomId }
    // WebViewClient is created only once by AndroidView.factory. Read the latest
    // Compose state from stable State holders so an APK restart/provider switch
    // cannot leave the visible page detached from the relay automations.
    val currentBrowserProvider by rememberUpdatedState(provider)
    val currentActiveCustom by rememberUpdatedState(activeCustom)
    val providerLabel = activeCustom?.name ?: provider.displayName
    val providerLoginUrl = activeCustom?.loginUrl ?: provider.loginUrl.orEmpty()
    val jsBridge = remember {
        WebSessionJsBridge(
            callback = { sessionJson ->
                val accessToken = WebCookieSnapshot.chatGptAccessToken(sessionJson)
                if (accessToken.isBlank()) {
                    toast = "ChatGPT 登录存在，但会话响应没有 accessToken"
                } else {
                    currentSaveCallback(
                        WebProviderType.CHATGPT_WEB,
                        pendingAccountName,
                        pendingCookie,
                        accessToken,
                        emptyMap(),
                        emptyMap(),
                    )
                    toast = "已保存并验证 ChatGPT 登录会话"
                }
                pendingCookie = ""
            },
            onSaveImageUrl = { imageUrl ->
                browserScope.launch {
                    toast = "正在保存原图…"
                    val bytes = fetchWebViewImageBytes(context, imageUrl)
                    if (bytes != null) {
                        val ok = saveImageToGallery(context, bytes)
                        toast = if (ok) "原图已保存到相册" else "图片保存失败"
                        LogStore.i("WebDownload", "onSaveImage 保存结果 ok=$ok size=${bytes.size}")
                    } else {
                        LogStore.w("WebDownload", "onSaveImage 下载失败 $imageUrl")
                        toast = "原图下载失败（链接失效）"
                    }
                }
            },
        )
    }

    val retainedWebView = webViewRef.value
    LaunchedEffect(requestedProvider, requestedModelId, customProviders, retainedWebView) {
        if (requestedProvider == WebProviderType.CUSTOM_WEB) {
            val config = customProviders.firstOrNull {
                it.modelId == requestedModelId || customWebImageModelId(it) == requestedModelId
            } ?: return@LaunchedEffect
            activeCustomId = config.id
            provider = WebProviderType.CUSTOM_WEB
            url = config.loginUrl
            retainedWebView?.loadUrl(config.loginUrl)
            return@LaunchedEffect
        }
        val target = requestedProvider?.takeIf { it.supportsWebLogin } ?: return@LaunchedEffect
        val view = retainedWebView ?: return@LaunchedEffect
        activeCustomId = null
        val expectedHost = Uri.parse(target.loginUrl).host.orEmpty()
        if (provider != target || !view.url.orEmpty().contains(expectedHost, ignoreCase = true)) {
            provider = target
            compatibilityMode = false
            url = target.loginUrl.orEmpty()
            view.settings.userAgentString = webLoginUserAgent(context, target, false)
            view.loadUrl(url)
            LogStore.i("Browser", "按中转模型自动激活 ${target.displayName}")
        }
    }

    toast?.let {
        LaunchedEffect(it) {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }

    if (showCustomDialog) {
        var customName by remember { mutableStateOf("") }
        var customModel by remember { mutableStateOf("") }
        var customUrl by remember { mutableStateOf("") }
        var inputSelector by remember { mutableStateOf("") }
        var sendSelector by remember { mutableStateOf("") }
        var responseSelector by remember { mutableStateOf("") }
        var showAdvanced by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("添加网页登录供应商") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("只需填写网页地址，名称、模型名和网页控件会自动识别。")
                    OutlinedTextField(customUrl, { customUrl = it }, label = { Text("网页地址") }, singleLine = true)
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "收起高级设置 ▲" else "高级设置（可选）▼")
                    }
                    if (showAdvanced) {
                        OutlinedTextField(customName, { customName = it }, label = { Text("供应商名称（留空自动生成）") }, singleLine = true)
                        OutlinedTextField(customModel, { customModel = it }, label = { Text("模型名（留空自动生成）") }, singleLine = true)
                        OutlinedTextField(inputSelector, { inputSelector = it }, label = { Text("输入框 CSS（留空自动识别）") }, singleLine = true)
                        OutlinedTextField(sendSelector, { sendSelector = it }, label = { Text("发送按钮 CSS（留空自动识别）") }, singleLine = true)
                        OutlinedTextField(responseSelector, { responseSelector = it }, label = { Text("回复内容 CSS（留空自动识别）") }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalizedUrl = customUrl.trim().let {
                        if (it.isNotBlank() && !it.contains("://")) "https://$it" else it
                    }
                    val host = runCatching { Uri.parse(normalizedUrl).host.orEmpty() }.getOrDefault("")
                        .removePrefix("www.")
                    val hostSlug = host.lowercase()
                        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
                        .ifBlank { "custom" }
                    if (normalizedUrl.isBlank() || host.isBlank()) {
                        toast = "请填写正确的网页地址"
                    } else {
                        val imageProvider = isKnownCustomWebImagePage(normalizedUrl)
                        val config = CustomWebProviderConfig(
                            name = customName.trim().ifBlank {
                                host.substringBeforeLast('.', host).replaceFirstChar { it.uppercase() }
                            },
                            modelId = customModel.trim().ifBlank {
                                if (imageProvider) "$hostSlug-image-web" else "$hostSlug-web"
                            },
                            loginUrl = normalizedUrl,
                            inputSelector = inputSelector.trim(), sendSelector = sendSelector.trim(),
                            responseSelector = responseSelector.trim(),
                            imageProvider = imageProvider,
                        )
                        onCustomProviderSaved(config)
                        activeCustomId = config.id
                        provider = WebProviderType.CUSTOM_WEB
                        url = config.loginUrl
                        webViewRef.value?.loadUrl(config.loginUrl)
                        showCustomDialog = false
                    }
                }) { Text("保存并打开") }
            },
            dismissButton = { TextButton(onClick = { showCustomDialog = false }) { Text("取消") } }
        )
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                OutlinedButton(onClick = { providerMenu = true }) {
                    Text(providerLabel + " ▼")
                }
                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    providers.forEach { item ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        provider = item
                                        activeCustomId = null
                                        capturedAuthorization.set("")
                            capturedDeepSeekFingerprint.value = emptyMap()
                                        compatibilityMode = false
                                        url = item.loginUrl.orEmpty()
                                        accountName = ""
                                        providerMenu = false
                                        webViewRef.value?.let { view ->
                                            view.settings.userAgentString = webLoginUserAgent(context, item, false)
                                            view.loadUrl(url)
                                        }
                                    },
                                    onLongClick = { pendingHideProvider = item }
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(item.displayName)
                        }
                    }
                    customProviders.forEach { item ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        provider = WebProviderType.CUSTOM_WEB
                                        activeCustomId = item.id
                                        url = item.loginUrl
                                        providerMenu = false
                                        webViewRef.value?.let { view ->
                                            // 自定义 Web（longcat 等）同样去掉 UA 的 wv 标记，
                                            // 否则网页检测到 WebView 环境会禁用图生视频等上传功能。
                                            // longcat 需要更彻底的 UA 伪装，否则发送按钮被禁用
                                            val ua = if (item.loginUrl.contains("longcat.chat", ignoreCase = true)) {
                                                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                                            } else {
                                                webLoginUserAgent(context, WebProviderType.CUSTOM_WEB, false)
                                            }
                                            view.settings.userAgentString = ua
                                            view.loadUrl(item.loginUrl)
                                        }
                                    },
                                    onLongClick = { pendingDeleteCustom = item }
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(item.name)
                        }
                    }
                    if (hiddenProviderList.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("已隐藏的供应商（${hiddenProviderList.size}）") },
                            onClick = { hiddenMenu = true }
                        )
                        DropdownMenu(expanded = hiddenMenu, onDismissRequest = { hiddenMenu = false }) {
                            hiddenProviderList.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.displayName) },
                                    onClick = {
                                        val newSet = hiddenProviders - item.name
                                        hiddenProviders = newSet
                                        persistHidden(newSet)
                                        hiddenMenu = false
                                    }
                                )
                            }
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("＋ 添加网页登录") },
                        onClick = { providerMenu = false; showCustomDialog = true }
                    )
                }
            }
            pendingHideProvider?.let { item ->
                AlertDialog(
                    onDismissRequest = { pendingHideProvider = null },
                    title = { Text("隐藏供应商") },
                    text = { Text("确定要隐藏「${item.displayName}」吗？隐藏后可在「已隐藏的供应商」中恢复。") },
                    confirmButton = {
                        TextButton(onClick = {
                            val newSet = hiddenProviders + item.name
                            hiddenProviders = newSet
                            persistHidden(newSet)
                            pendingHideProvider = null
                        }) { Text("隐藏") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingHideProvider = null }) { Text("取消") }
                    }
                )
            }
            pendingDeleteCustom?.let { item ->
                AlertDialog(
                    onDismissRequest = { pendingDeleteCustom = null },
                    title = { Text("删除供应商") },
                    text = { Text("确定要删除「${item.name}」吗？此操作不可恢复。") },
                    confirmButton = {
                        TextButton(onClick = {
                            onCustomProviderDeleted(item.id)
                            pendingDeleteCustom = null
                        }) { Text("删除") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteCustom = null }) { Text("取消") }
                    }
                )
            }
            Spacer(Modifier.width(8.dp))
            if (provider == WebProviderType.RAPHAEL_WEB) {
                Text("免登录免费基础生图", modifier = Modifier.weight(1f))
            } else if (provider == WebProviderType.ARENA_GUEST) {
                Text("免登录游客对话，首次需同意网页条款", modifier = Modifier.weight(1f))
            } else if (provider == WebProviderType.CUSTOM_WEB) {
                Text("登录状态由网页自动保存", modifier = Modifier.weight(1f))
            } else {
                OutlinedTextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    label = { Text("账号名称") },
                    placeholder = { Text("例如：我的 $providerLabel") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("网址") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = { webViewRef.value?.loadUrl(url) }) { Text("前往") }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = {
                val u = "https://bailian.console.aliyun.com/cn-beijing/model/experience/llm"
                url = u
                webViewRef.value?.loadUrl(u)
            }) { Text("百炼") }
            Spacer(Modifier.width(8.dp))
            if (provider == WebProviderType.RAPHAEL_WEB) {
                Text("打开页面后即可通过 raphael-image-web 调用", modifier = Modifier.weight(2f))
            } else if (provider == WebProviderType.ARENA_GUEST) {
                Text("首次点 Agree 后即可通过 arena-guest-web 调用", modifier = Modifier.weight(2f))
            } else if (provider == WebProviderType.CUSTOM_WEB) {
                Text("登录后直接在中转选择 ${activeCustom?.modelId.orEmpty()} 调用")
            } else Button(onClick = {
                val cm = CookieManager.getInstance()
                cm.flush()
                val cookies = WebCookieSnapshot.merge(provider.cookieOrigins.map { origin ->
                    cm.getCookie(origin).orEmpty()
                })
                val capturedToken = capturedAuthorization.get()
                if (provider == WebProviderType.KIMI_WEB || provider == WebProviderType.DEEPSEEK_WEB) {
                    val token = capturedToken.ifBlank {
                        if (provider == WebProviderType.KIMI_WEB)
                            com.wenwei.deepseekproxy.proxy.KimiClient.extractToken(cookies)
                        else ""
                    }
                    if (token.isBlank()) {
                        toast = "尚未捕获授权：请登录 ${provider.displayName} 后先发送一条消息，再点保存会话"
                    } else {
                        val extraHeaders: Map<String, String> = if (provider == WebProviderType.DEEPSEEK_WEB) {
                            capturedDeepSeekFingerprint.value
                        } else emptyMap()
                        onSessionSaved(provider, accountName, cookies, token, emptyMap(), extraHeaders)
                        val fpNote = if (extraHeaders.isNotEmpty()) "（含设备指纹）" else ""
                        toast = "已保存 ${provider.displayName} 授权会话$fpNote"
                    }
                } else if (cookies.isBlank()) {
                    toast = "未获取到 Cookie，请先登录 ${provider.displayName}"
                } else {
                    if (provider == WebProviderType.CHATGPT_WEB) {
                        pendingCookie = cookies
                        pendingAccountName = accountName
                        webViewRef.value?.evaluateJavascript(
                            "fetch('/api/auth/session',{credentials:'include'})" +
                                ".then(r=>r.text()).then(t=>MultiWebBridge.onChatGptSession(t))" +
                                ".catch(e=>MultiWebBridge.onChatGptSession('{}'))",
                            null
                        )
                    } else if (provider == WebProviderType.DOUBAO_WEB &&
                        !WebCookieSnapshot.hasDoubaoLoginCookie(cookies)
                    ) {
                        toast = "当前只有豆包访客 Cookie，尚未完成登录；请登录成功后再保存会话"
                    } else if (provider == WebProviderType.GEMINI_WEB) {
                        val view = webViewRef.value
                        if (view == null) {
                            toast = "Gemini 页面尚未打开"
                        } else {
                            geminiLoginState(view) { signedIn, message ->
                                if (!signedIn) {
                                    toast = message
                                    return@geminiLoginState
                                }
                                val byOrigin = provider.cookieOrigins.associateWith { origin ->
                                    cm.getCookie(origin).orEmpty()
                                }.filterValues(String::isNotBlank)
                                if (byOrigin.isEmpty()) {
                                    toast = "Gemini 登录已显示，但没有取得按域 Cookie"
                                } else {
                                    onSessionSaved(provider, accountName, cookies, "", byOrigin, emptyMap())
                                    toast = "已按域保存并验证 Gemini 登录会话"
                                }
                            }
                        }
                    } else if (provider == WebProviderType.ZHIPU_WEB) {
                        val view = webViewRef.value
                        if (view == null) {
                            toast = "清言页面尚未打开"
                        } else {
                            view.evaluateJavascript(
                                """
                                (()=>{let token='';try{for(let i=0;i<localStorage.length;i++){const v=String(localStorage.getItem(localStorage.key(i))||'');const bare=v.replace(/^Bearer\s+/i,'').replace(/^"|"$/g,'');if(!token&&/^eyJ/.test(bare))token=bare;}}catch(e){}return token;})()
                                """.trimIndent()
                            ) { raw ->
                                val token = decodeJavascriptString(raw)
                                onSessionSaved(provider, accountName, cookies, token, emptyMap(), emptyMap())
                                toast = if (token.isNotBlank()) {
                                    "已保存并验证 ${provider.displayName} 登录会话"
                                } else {
                                    "已保存 Cookie（未抓到 token；如调用失败，请在清言页面发一条消息后重新保存）"
                                }
                            }
                        }
                    } else {
                        onSessionSaved(provider, accountName, cookies, "", emptyMap(), emptyMap())
                        toast = "已保存并验证 ${provider.displayName} 登录会话"
                    }
                }
            }) { Text("保存会话") }
            if (provider != WebProviderType.RAPHAEL_WEB &&
                provider != WebProviderType.ARENA_GUEST &&
                provider != WebProviderType.CUSTOM_WEB
            ) Spacer(Modifier.width(8.dp))
            if (provider != WebProviderType.RAPHAEL_WEB &&
                provider != WebProviderType.ARENA_GUEST &&
                provider != WebProviderType.CUSTOM_WEB
            ) Button(onClick = {
                clearProviderCookies(provider)
                pendingStorageWipe = true   // 页面加载完再清 localStorage（Next.js 站点登录态存那里）
                webViewRef.value?.loadUrl(url)
                toast = "已清空 ${provider.displayName} Cookie，正在清本地存储…"
            }) { Text("重置当前登录") }
        }
        // 会话列表：默认折叠成一行摘要，点开才展开（省纵向空间）
        val providerSessions = sessions.filter { it.providerType == provider }
        if (providerSessions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { sessionsExpanded = !sessionsExpanded }) {
                    Text(
                        (if (sessionsExpanded) "▾ " else "▸ ") +
                            "已存会话 ${providerSessions.size} 个（${providerSessions.joinToString { it.displayName }}）",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (sessionsExpanded) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                items(providerSessions, key = { it.id }) { session ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(session.displayName, maxLines = 1)
                            sessionStatus[session.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        TextButton(onClick = {
                            val cm = CookieManager.getInstance()
                            if (provider == WebProviderType.GEMINI_WEB) {
                                if (session.cookieHeadersByOrigin.isEmpty()) {
                                    toast = "这是旧版 Gemini 会话，Cookie 域信息已丢失，请重新登录并保存"
                                } else {
                                    val view = webViewRef.value
                                    if (view == null) {
                                        toast = "Gemini 页面尚未创建"
                                    } else {
                                        restoreOriginCookiesThenLoad(
                                            view = view,
                                            cookieHeadersByOrigin = session.cookieHeadersByOrigin,
                                            targetUrl = provider.loginUrl.orEmpty(),
                                        ) {
                                            pendingGeminiValidationSessionId = session.id
                                            currentSessionStatusCallback(session.id, "Gemini 页面加载中…")
                                            toast = "已按域载入 ${session.displayName}，请等待 Gemini 页面验证"
                                        }
                                    }
                                }
                            } else {
                                // 先清后写：避免上一个账号的 Cookie 残留与本会话混串
                                clearProviderCookies(provider)
                                provider.cookieOrigins.forEach { origin ->
                                    session.cookieHeader.split(';').map { it.trim() }
                                        .filter { it.contains('=') }
                                        .forEach { cm.setCookie(origin, it) }
                                }
                                cm.flush()
                                webViewRef.value?.loadUrl(provider.loginUrl.orEmpty())
                                toast = "已载入 ${session.displayName}"
                            }
                        }) { Text("载入") }
                        TextButton(onClick = {
                            if (provider != WebProviderType.GEMINI_WEB) {
                                onSessionTest(session)
                            } else if (session.cookieHeadersByOrigin.isEmpty()) {
                                val message = "旧版 Gemini 会话缺少 Cookie 域信息，请重新登录并保存"
                                currentSessionStatusCallback(session.id, message)
                                toast = message
                            } else {
                                val view = webViewRef.value
                                if (view == null) {
                                    toast = "Gemini 页面尚未创建"
                                } else {
                                    pendingGeminiValidationSessionId = session.id
                                    currentSessionStatusCallback(session.id, "Gemini 页面加载中…")
                                    restoreOriginCookiesThenLoad(
                                        view,
                                        session.cookieHeadersByOrigin,
                                        provider.loginUrl.orEmpty(),
                                    ) {}
                                }
                            }
                        }) { Text("验证") }
                        TextButton(onClick = { onSessionDeleted(session.id) }) { Text("删除") }
                    }
                }
            }
            }
        }
        // WorkBuddy 网页登录授权（插件 token 流）入口与状态，自包含组件见 workbuddy/WorkBuddyStatusCard.kt
        WorkBuddyStatusCard(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
        if (provider == WebProviderType.CHATGPT_WEB) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("WebView：$webViewInfo", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    compatibilityMode = true
                    webViewRef.value?.apply {
                        settings.userAgentString = webLoginUserAgent(context, provider, true)
                        clearCache(false)
                        loadUrl(provider.loginUrl.orEmpty())
                    }
                    LogStore.i("Browser", "启用 ChatGPT 华为兼容模式，内核=$webViewInfo")
                    toast = "已启用兼容模式并刷新（不会清除登录 Cookie）"
                }) { Text(if (compatibilityMode) "兼容模式已开" else "兼容刷新") }
            }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
        } else {
            HorizontalDivider()
        }
        key(webViewGeneration) { AndroidView(
            factory = { ctx ->
                TouchWebView(ctx).apply {
                    // 关键：AndroidView 默认给子 View 设 WRAP_CONTENT，WebView 在内容未布局前会塌成 0 高度导致空白。
                    // 必须显式 MATCH_PARENT 让其撑满父容器。
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    // ChatGPT login may open OAuth in a popup. Several Huawei/Honor
                    // WebView builds do not fold that popup back into the opener, so
                    // the account is authenticated while the visible page stays on login.
                    settings.setSupportMultipleWindows(true)
                    // 豆包例外：对齐墨听D AiWebRelayHost 的登录浏览器配置——
                    // 单窗口 + 禁止 JS 自动开窗（window.open 被无视，页面不会被导航走）。
                    // 此前"允许开窗+单窗口"的组合会让豆包登录后的 window.open 把当前页顶掉
                    // （表现为登录完成瞬间被弹出）。
                    if (provider == WebProviderType.DOUBAO_WEB) {
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                    }
                    // Automation must be able to focus the page editor even while the
                    // browser tab is covered by the relay screen.
                    isFocusable = true
                    isFocusableInTouchMode = true
                    addJavascriptInterface(jsBridge, "MultiWebBridge")
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    WebView.setWebContentsDebuggingEnabled(true)
                    settings.userAgentString = if (provider == WebProviderType.DOUBAO_WEB) {
                        // 豆包会校验真实 WebView 指纹，保留系统 UA。
                        webLoginUserAgent(ctx, provider, compatibilityMode)
                    } else {
                        // LongCat 等网页登录在 Android 12 WebView 96 下会因 ; wv
                        // 禁用文字/图片/视频提交；必须在首次 load 前使用标准移动 Chrome UA。
                        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    }
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.loadsImagesAutomatically = true
                    // 按设备宽度渲染，避免桌面宽版面只显示左上角、无法左右移动
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.mixedContentMode =
                        android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // 长按网页图片/视频 → 保存到相册（先 OkHttp 直下，失效则从 WebView 缓存提取字节）
                    setOnLongClickListener {
                        val ht = hitTestResult
                        val isImage = ht != null && (
                            ht.type == android.webkit.WebView.HitTestResult.IMAGE_TYPE ||
                                ht.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                            ) && !ht.extra.isNullOrBlank()
                        // 视频：链接指向 mp4/webm/mov/mkv 等，或 extra 以视频扩展名结尾
                        val isVideoUrl = ht != null && !ht.extra.isNullOrBlank() &&
                            Regex("\\.(mp4|webm|mov|mkv|m4v)(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(ht.extra!!)
                        if (isImage || isVideoUrl) {
                            val mediaUrl = ht.extra!!
                            browserScope.launch {
                                toast = if (isVideoUrl) "正在保存视频…" else "正在保存图片…"
                                val bytes = fetchWebViewImageBytes(context, mediaUrl)
                                if (bytes != null) {
                                    val ok = if (isVideoUrl || isVideoBytes(bytes)) {
                                        saveVideoToGallery(context, bytes)
                                    } else {
                                        saveImageToGallery(context, bytes)
                                    }
                                    toast = when {
                                        ok && isVideoUrl -> "视频已保存到相册"
                                        ok -> "已保存到相册"
                                        else -> "保存失败"
                                    }
                                } else {
                                    toast = "媒体链接已失效，且本地无缓存，请重新生成后保存"
                                }
                            }
                            true
                        } else {
                            false
                        }
                    }
                    // 捕获网页内下载（豆包「下载」按钮触发 a[download]/blob 下载）→ 保存到相册
                    setDownloadListener { url, _ /* userAgent */, contentDisposition, mimetype, _ /* contentLength */ ->
                        if (!url.isNullOrBlank()) {
                            LogStore.i("WebDownload", "捕获下载 URL=${url.take(140)} mime=$mimetype")
                            browserScope.launch {
                                // 视频：mp4/mov/webm 等 → 存相册视频目录；其余按图片处理
                                val isVideo = (mimetype?.startsWith("video/") == true) ||
                                    url.endsWith(".mp4") || url.endsWith(".mov") ||
                                    url.endsWith(".webm") || url.endsWith(".mkv") ||
                                    (contentDisposition?.contains("mp4", ignoreCase = true) == true)
                                toast = if (isVideo) "正在保存视频…" else "正在保存图片…"
                                val bytes = if (url.startsWith("blob:")) {
                                    fetchBlobBytes(this@apply, url)
                                } else {
                                    fetchWebViewImageBytes(context, url)
                                }
                                if (bytes != null) {
                                    val ok = if (isVideo || isVideoBytes(bytes)) {
                                        saveVideoToGallery(context, bytes)
                                    } else {
                                        saveImageToGallery(context, bytes)
                                    }
                                    toast = when {
                                        ok && isVideo -> "视频已保存到相册"
                                        ok -> "已保存到相册"
                                        else -> "保存失败"
                                    }
                                    LogStore.i("WebDownload", "保存结果 ok=$ok size=${bytes.size} isVideo=$isVideo")
                                } else {
                                    LogStore.w("WebDownload", "下载失败 URL=${url.take(140)}")
                                    toast = "下载失败（链接失效）"
                                }
                            }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val host = request?.url?.host.orEmpty()
                            if (host.endsWith("kimi.com") || host.endsWith("deepseek.com")) {
                                request?.requestHeaders?.entries?.firstOrNull {
                                    it.key.equals("Authorization", ignoreCase = true)
                                }?.value?.takeIf(String::isNotBlank)?.let { capturedAuthorization.set(it) }
                                request?.requestHeaders?.entries?.firstOrNull {
                                    it.key.equals("x-hif-leim", ignoreCase = true)
                                }?.value?.takeIf(String::isNotBlank)?.let { fp ->
                                    capturedDeepSeekFingerprint.value = mapOf("x-hif-leim" to fp)
                                }
                            }
                            // 百炼：页面自身 API 请求带 bx 反爬三件套，拦下即存（体验页发一条消息触发）
                            if (host == "bailian-cs.console.aliyun.com") {
                                val h = request?.requestHeaders
                                fun hdr(name: String) = h?.entries?.firstOrNull {
                                    it.key.equals(name, ignoreCase = true)
                                }?.value
                                val bxUa = hdr("bx-ua")
                                val bxEt = hdr("bx_et")
                                val bxUmid = hdr("bx-umidtoken")
                                if (!bxUa.isNullOrBlank() && !bxEt.isNullOrBlank()) {
                                    val cookie = android.webkit.CookieManager.getInstance()
                                        .getCookie("https://bailian.console.aliyun.com").orEmpty()
                                    val json = org.json.JSONObject().apply {
                                        put("cookies", cookie)
                                        put("bx_ua", bxUa)
                                        put("bx_et", bxEt)
                                        if (!bxUmid.isNullOrBlank()) put("bx_umidtoken", bxUmid)
                                        put("model_id", "qwen3.8-max")
                                    }
                                    onBailianCaptured(json.toString())
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            val target = request?.url?.toString().orEmpty()
                            if (view == null || target.isBlank()) return false
                            return if (target.startsWith("intent://", ignoreCase = true)) {
                                openWebLoginUrlInSameView(view, target)
                            } else {
                                // Keep ordinary HTTPS redirects in this WebView so the
                                // login Cookie and the final ChatGPT page share one jar.
                                false
                            }
                        }

                        @Suppress("DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView?, target: String?): Boolean {
                            if (view == null || target.isNullOrBlank()) return false
                            return if (target.startsWith("intent://", ignoreCase = true)) {
                                openWebLoginUrlInSameView(view, target)
                            } else false
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            urlStr: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            urlStr?.let { url = it }
                            userTouchedWebView = false   // 新页面加载，重置触摸标记
                            if (view != null) {
                                if (currentBrowserProvider == WebProviderType.CUSTOM_WEB &&
                                    currentActiveCustom?.let {
                                        !isCustomWebViewOnProviderPage(it, urlStr.orEmpty())
                                    } == true
                                ) {
                                    com.wenwei.deepseekproxy.proxy.CustomWebChatAutomation.detach(view)
                                    com.wenwei.deepseekproxy.proxy.CustomWebImageAutomation.detach(view)
                                    com.wenwei.deepseekproxy.proxy.CustomWebVideoAutomation.detach(view)
                                }
                                if (!urlStr.orEmpty().contains("chatgpt.com", ignoreCase = true)) {
                                    com.wenwei.deepseekproxy.proxy.ChatGptWebChatAutomation.detach(view)
                                    com.wenwei.deepseekproxy.proxy.ChatGptWebAutomation.detach(view)
                                }
                                if (!urlStr.orEmpty().contains("gemini.google.com", ignoreCase = true)) {
                                    com.wenwei.deepseekproxy.proxy.GeminiWebChatAutomation.detach(view)
                                    com.wenwei.deepseekproxy.proxy.GeminiWebImageAutomation.detach(view)
                                }
                                if (!urlStr.orEmpty().contains("raphael.app", ignoreCase = true)) {
                                    com.wenwei.deepseekproxy.proxy.RaphaelWebImageAutomation.detach(view)
                                }
                                if (provider != WebProviderType.CUSTOM_WEB &&
                                    !com.wenwei.deepseekproxy.proxy.isBuiltInBrowserRelay(provider)
                                ) {
                                    com.wenwei.deepseekproxy.proxy.CustomWebChatAutomation.detach(view)
                                    com.wenwei.deepseekproxy.proxy.CustomWebImageAutomation.detach(view)
                                    com.wenwei.deepseekproxy.proxy.CustomWebVideoAutomation.detach(view)
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView?, urlStr: String?) {
                            progress = 0
                            CookieManager.getInstance().flush()
                            // 「重置当前登录」请求的 localStorage 清空（cnb.cool 等站点登录态存 localStorage，
                            // 只清 Cookie 无法登出）——页面就绪后清存储并刷新一次
                            if (view != null && pendingStorageWipe) {
                                val host = android.net.Uri.parse(urlStr.orEmpty()).host.orEmpty()
                                val matchOrigin = provider.cookieOrigins.any { origin ->
                                    host.isNotEmpty() && origin.contains(host, ignoreCase = true)
                                }
                                if (matchOrigin) {
                                    pendingStorageWipe = false
                                    view.evaluateJavascript(
                                        "try{localStorage.clear();sessionStorage.clear();}catch(e){};true", null
                                    )
                                    toast = "已清空 ${provider.displayName} 的 Cookie 与本地存储"
                                    view.postDelayed({ view.reload() }, 200L)
                                }
                            }
                            if (view != null && urlStr.orEmpty().contains("chatgpt.com", ignoreCase = true)
                            ) {
                                com.wenwei.deepseekproxy.proxy.ChatGptWebAutomation.attach(view)
                                com.wenwei.deepseekproxy.proxy.ChatGptWebChatAutomation.attach(view)
                            }
                            if (view != null && urlStr.orEmpty().contains("gemini.google.com", ignoreCase = true)
                            ) {
                                com.wenwei.deepseekproxy.proxy.GeminiWebChatAutomation.attach(view)
                                com.wenwei.deepseekproxy.proxy.GeminiWebImageAutomation.attach(view)
                                pendingGeminiValidationSessionId?.let { sessionId ->
                                    view.postDelayed({
                                        if (pendingGeminiValidationSessionId != sessionId) return@postDelayed
                                        geminiLoginState(view) { _, message ->
                                            currentSessionStatusCallback(sessionId, message)
                                            toast = message
                                            pendingGeminiValidationSessionId = null
                                        }
                                    }, 800L)
                                }
                            }
                            if (view != null && urlStr.orEmpty().contains("raphael.app", ignoreCase = true)
                            ) {
                                com.wenwei.deepseekproxy.proxy.RaphaelWebImageAutomation.attach(view)
                            }
                            // 豆包：已登录时自动关闭页面自带的登录引导弹窗（纯打扰）。
                            // 关键门槛：Cookie 里已有 sessionid（=已登录）才动手；未登录时绝不碰——
                            // 那个弹窗正是登录入口（手机号/二维码都在里面），此前误关导致"登录被弹出"。
                            // 触摸标记仅作辅助；不再重试、延迟拉长到 2s 降低误伤面。
                            if (view != null && !userTouchedWebView &&
                                urlStr.orEmpty().contains("doubao.com", ignoreCase = true) &&
                                !urlStr.orEmpty().contains("login", ignoreCase = true) &&
                                !urlStr.orEmpty().contains("passport", ignoreCase = true) &&
                                CookieManager.getInstance().getCookie("https://www.doubao.com")
                                    ?.contains("sessionid") == true
                            ) {
                                view.postDelayed({
                                    view.evaluateJavascript(
                                        """
                                        (function closeDoubaoLoginModal() {
                                          var sels = [
                                            '[data-testid*="close" i]', '[class*="close" i][aria-label]',
                                            '[aria-label*="关闭"]', '[class*="modal"] [class*="close" i]',
                                            '[class*="dialog"] [class*="close" i]'
                                          ];
                                          for (var i = 0; i < sels.length; i++) {
                                            var els = document.querySelectorAll(sels[i]);
                                            for (var j = 0; j < els.length; j++) {
                                              var e = els[j];
                                              var r = e.getBoundingClientRect();
                                              if (r.width > 0 && r.height > 0) {
                                                var inModal = e.closest('[class*="modal" i],[class*="dialog" i],[role="dialog"]') != null;
                                                var txt = (e.textContent||'') + '';
                                                if (inModal || /close|关闭|×/i.test(txt)) {
                                                  e.click();
                                                  return 'closed:' + sels[i];
                                                }
                                              }
                                            }
                                          }
                                          return '';
                                        })();
                                        """.trimIndent(), null
                                    )
                                }, 2000L)
                            }
                            if (view != null && currentBrowserProvider == WebProviderType.CUSTOM_WEB) {
                                currentActiveCustom?.takeIf {
                                    isCustomWebViewOnProviderPage(it, urlStr.orEmpty())
                                }?.let {
                                    com.wenwei.deepseekproxy.proxy.CustomWebChatAutomation.attach(view, it.id)
                                    com.wenwei.deepseekproxy.proxy.CustomWebImageAutomation.attach(view, it.id)
                                    com.wenwei.deepseekproxy.proxy.CustomWebVideoAutomation.attach(view, it.id)
                                    // 常驻拦截"暂无权限访问"类 toast（LongCat 等网页加载时闪出）
                                    com.wenwei.deepseekproxy.proxy.CustomWebChatAutomation.armPermissionToastBlocker(view)
                                    // longcat 聊天模式发送按钮被锁，强制解锁
                                    if (urlStr?.contains("longcat.chat", ignoreCase = true) == true) {
                                        view.postDelayed({
                                            view.evaluateJavascript("""
                                                (()=>{try{
                                                  if(window.__multiwebSendUnlocker)return'dup';
                                                  const enable=()=>{
                                                    const b=document.querySelector('.send-btn');
                                                    const e=document.querySelector('[contenteditable][role="textbox"]');
                                                    if(!b||!e)return;
                                                    const has=(e.innerText||'').trim().length>0;
                                                    if(has){
                                                      b.disabled=false;
                                                      b.removeAttribute('aria-disabled');
                                                      b.classList.remove('send-btn-disabled');
                                                      b.style.opacity='1';
                                                      b.style.pointerEvents='auto';
                                                      b.style.cursor='pointer';
                                                      // 保留网页原生触摸/点击链路，不注入 b.click()，避免破坏 Vue 的 trusted event 判断
                                                    }
                                                  };
                                                  enable();
                                                  const mo=new MutationObserver(enable);
                                                  mo.observe(document.documentElement||document.body,{childList:true,subtree:true,characterData:true});
                                                  window.__multiwebSendUnlocker=mo;
                                                  return'unlocked';
                                                }catch(e){return String(e)}})()
                                            """.trimIndent(), null)
                                        }, 1500)
                                    }
                                    currentRelayReadyCallback()
                                }
                            }
                            if (view != null && com.wenwei.deepseekproxy.proxy.isBuiltInBrowserRelay(currentBrowserProvider)) {
                                val config = com.wenwei.deepseekproxy.proxy.builtInBrowserRelayConfig(currentBrowserProvider)
                                com.wenwei.deepseekproxy.proxy.CustomWebChatAutomation.attach(view, config.id)
                                // 内置中继的生图自动化也要挂接（竞技场免登录生图等），
                                // 否则 generateImage 永远等不到 WebView 报 webview_not_ready
                                com.wenwei.deepseekproxy.proxy.CustomWebImageAutomation.attach(view, config.id)
                                com.wenwei.deepseekproxy.proxy.CustomWebChatAutomation.armPermissionToastBlocker(view)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                LogStore.e("Browser", "页面加载错误: ${error?.errorCode} ${error?.description}")
                                toast = "页面加载失败: ${error?.description}"
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            if (request?.isForMainFrame == true) {
                                LogStore.e("Browser", "HTTP 错误: ${errorResponse?.statusCode}")
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            handler?.cancel()
                            LogStore.e("Browser", "SSL 错误: ${error?.primaryError} ${error?.url}")
                            toast = "SSL证书校验失败，请检查系统时间、网络或WebView"
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?
                        ): Boolean {
                            LogStore.e("Browser", "WebView渲染进程退出，崩溃=${detail?.didCrash()}，准备重建")
                            view?.destroy()
                            webViewRef.value = null
                            webViewGeneration++
                            toast = "浏览器内核异常，已自动重建"
                            return true
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            if (filePathCallback == null) return false
                            android.util.Log.i("NativeWeb", "WebChromeClient.onShowFileChooser")
                            val activity = context as? MainActivity ?: return false
                            activity.openWebFileChooser(filePathCallback)
                            return true
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            val parent = view ?: return false
                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                            val popup = WebView(parent.context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = parent.settings.userAgentString
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        popupView: WebView?,
                                        request: android.webkit.WebResourceRequest?
                                    ): Boolean {
                                        val target = request?.url?.toString().orEmpty()
                                        if (target.isBlank()) return false
                                        openWebLoginUrlInSameView(parent, target)
                                        popupView?.destroy()
                                        return true
                                    }

                                    @Suppress("DEPRECATION")
                                    override fun shouldOverrideUrlLoading(
                                        popupView: WebView?,
                                        target: String?
                                    ): Boolean {
                                        if (target.isNullOrBlank()) return false
                                        openWebLoginUrlInSameView(parent, target)
                                        popupView?.destroy()
                                        return true
                                    }
                                }
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                            transport.webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    val wv = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(wv, true)
                    }
                    // 用户碰过 WebView 后不再自动关豆包登录弹窗（避免误杀主动登录）
                    setOnTouchListener { _, _ ->
                        userTouchedWebView = true
                        false
                    }
                    loadUrl(url)
                }.also { webViewRef.value = it }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            update = {
                it.isFocusable = true
                it.isFocusableInTouchMode = true
                // 切换供应商时同步窗口模式（豆包=墨听D 同款：单窗口+禁JS自动开窗，其余多窗口）
                it.settings.setSupportMultipleWindows(provider != WebProviderType.DOUBAO_WEB)
                it.settings.javaScriptCanOpenWindowsAutomatically = provider != WebProviderType.DOUBAO_WEB
            },
            onRelease = {
                com.wenwei.deepseekproxy.proxy.CustomWebChatAutomation.detach(it)
                com.wenwei.deepseekproxy.proxy.CustomWebImageAutomation.detach(it)
                com.wenwei.deepseekproxy.proxy.CustomWebVideoAutomation.detach(it)
                com.wenwei.deepseekproxy.proxy.GeminiWebChatAutomation.detach(it)
                com.wenwei.deepseekproxy.proxy.GeminiWebImageAutomation.detach(it)
                com.wenwei.deepseekproxy.proxy.ChatGptWebChatAutomation.detach(it)
                com.wenwei.deepseekproxy.proxy.ChatGptWebAutomation.detach(it)
                com.wenwei.deepseekproxy.proxy.RaphaelWebImageAutomation.detach(it)
                it.destroy()
            }
        ) }
    }
}
