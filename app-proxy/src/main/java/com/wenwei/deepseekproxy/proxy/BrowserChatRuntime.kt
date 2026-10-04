package com.wenwei.deepseekproxy.proxy

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.net.URI
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Request-scoped DOM events. Installed once before loadUrl, so warm calls need no reload. */
object BrowserChatRuntime {
    private val main = Handler(Looper.getMainLooper())
    private val bridges = WeakHashMap<WebView, Bridge>() // main thread only
    @Volatile private var observerSource: String? = null

    fun install(view: WebView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (bridges.containsKey(view)) return
        val bridge = Bridge(view)
        bridges[view] = bridge
        view.addJavascriptInterface(bridge, "MultiWebChat")
        BrowserCallTelemetry.hostState = "mounted"
    }

    fun release(view: WebView, reason: String) {
        val bridge = bridges.remove(view) ?: return
        bridge.active?.events?.close(WebAutomationException("browser_closed", reason))
        view.removeJavascriptInterface("MultiWebChat")
        BrowserCallTelemetry.hostState = "closed"
    }

    fun pageChanged(view: WebView) {
        bridges[view]?.active?.events?.close(WebAutomationException("web_page_changed", "调用期间网页发生导航，本次未自动重发"))
        BrowserCallTelemetry.hostState = "loading"
    }

    /** Bound all native JS callbacks, including editor/attachment preparation. */
    internal suspend fun evaluate(view: WebView, script: String): String = webStageTimeout(
        8_000, "web_script_timeout", "网页脚本未响应，请检查浏览器页面") {
        suspendCancellableCoroutine { cont ->
            main.post {
                if (!cont.isActive) return@post
                try {
                    view.evaluateJavascript(script) { raw ->
                        if (cont.isActive) cont.resume(decode(raw))
                    }
                } catch (error: Exception) {
                    if (cont.isActive) cont.resumeWithException(error)
                }
            }
        }
    }

    internal suspend fun <T> watch(view: WebView, gemini: Boolean, prompt: String,
        options: WebChatOptions, block: suspend (Watch) -> T): T {
        val call = requireNotNull(currentCoroutineContext()[BrowserRelayCall])
        val source = observerSource ?: withContext(Dispatchers.IO) {
            view.context.assets.open("multiweb-chat-observer.js").bufferedReader().use { it.readText() }
        }.also { observerSource = it }
        val watch = Watch(view, call, if (gemini) "gemini.google.com" else "chatgpt.com", options)
        val bridge = withContext(Dispatchers.Main.immediate) {
            bridges[view] ?: throw WebAutomationException("web_bridge_missing", "网页需重新打开以初始化调用回执")
        }
        withContext(Dispatchers.Main.immediate) { bridge.active = watch }
        try {
            val config = JSONObject().put("requestId", call.id).put("gemini", gemini).put("prompt", prompt)
            if (watch.evaluate("($source)($config)") != "ready") throw WebAutomationException(
                "web_observer_failed", "网页响应监听初始化失败")
            val initial = watch.snapshot()
            if (initial.busy) throw WebAutomationException("generation_in_progress",
                "网页已有回答正在生成，请等待完成或在浏览器页停止后再试")
            watch.verifyModel(initial.pageModel)
            call.ready()
            return block(watch)
        } finally {
            // Queue release must not wait indefinitely for an unresponsive Android main thread.
            // A later call has a different ID; delayed cleanup cannot dispose its observer.
            watch.events.close()
            main.post {
                if (bridge.active === watch) {
                    bridge.active = null
                    if (bridges[view] === bridge) runCatching {
                        view.evaluateJavascript(
                            "if(window.__multiwebChat?.requestId===${JSONObject.quote(call.id)}) window.__multiwebChat.dispose()", null)
                    }
                }
            }
        }
    }

    private class Bridge(view: WebView) {
        val view = WeakReference(view)
        var active: Watch? = null // main thread only
        @JavascriptInterface fun onState(requestId: String, payload: String) {
            main.post {
                val watch = active ?: return@post
                val owner = view.get() ?: return@post
                if (requestId == watch.call.id && watch.owns(owner)) {
                    watch.events.trySend(payload)
                }
            }
        }
    }

    internal class Watch(private val view: WebView, val call: BrowserRelayCall,
        private val host: String, private val options: WebChatOptions) {
        val events = Channel<String>(Channel.CONFLATED)
        private var receipt: WebChatSnapshot? = null
        fun verifyModel(label: String) {
            val checked = try {
                verifyWebChatModel(options.requiredModel, label)
            } catch (error: WebAutomationException) {
                call.model(label, false)
                throw error
            }
            call.model(label, checked)
        }
        fun owns(candidate: WebView): Boolean = candidate === view &&
            runCatching { URI(view.url.orEmpty()).host == host }.getOrDefault(false)

        suspend fun evaluate(script: String): String = webStageTimeout(
            8_000, "web_script_timeout", "网页脚本未响应，请检查浏览器页面") {
            suspendCancellableCoroutine { cont ->
                main.post {
                    if (!cont.isActive) return@post
                    if (!owns(view)) {
                        cont.resumeWithException(WebAutomationException("web_page_changed", "网页已关闭或离开当前站点"))
                        return@post
                    }
                    try {
                        view.evaluateJavascript(script) { raw ->
                            if (cont.isActive) cont.resume(decode(raw))
                        }
                    } catch (error: Exception) { if (cont.isActive) cont.resumeWithException(error) }
                }
            }
        }

        suspend fun snapshot(): WebChatSnapshot = parse(evaluate(
            "window.__multiwebChat ? JSON.stringify(window.__multiwebChat.snapshot()) : ''"))
        private fun parse(raw: String): WebChatSnapshot {
            val state = runCatching { JSONObject(raw) }.getOrNull()
                ?: throw WebAutomationException("web_page_changed", "网页响应监听已失效，本次未自动重发")
            if (state.optString("requestId") != call.id) throw WebAutomationException(
                "web_request_mismatch", "网页响应不属于本次请求")
            if (state.optString("error").isNotBlank()) throw WebAutomationException(
                "web_page_changed", "调用期间切换了网页会话，本次未自动重发")
            val images = state.optJSONArray("images") ?: JSONArray()
            return WebChatSnapshot(state.optBoolean("acknowledged"), state.optBoolean("busy"),
                state.optBoolean("finished"), state.optString("text"),
                (0 until images.length()).map { images.optString(it) }.filter(String::isNotBlank),
                state.optString("pageModel"))
        }

        suspend fun send(nativeEnter: Boolean = false) {
            call.phase("填写并校验输入")
            webStageTimeout(8_000, "prompt_fill_failed", "网页输入内容未确认，本次未发送") {
                var settled = 0
                while (settled < 2) {
                    delay(150)
                    settled = if (evaluate("window.__multiwebChat.inputMatches()") == "true") settled + 1 else 0
                }
            }
            // Verify after filling too; the user may have changed models during preparation.
            val current = snapshot()
            if (current.busy) throw WebAutomationException("generation_in_progress", "网页已有回答正在生成，本次未发送")
            verifyModel(current.pageModel)
            call.phase("发送并确认接收")
            val dispatched = withTimeoutOrNull(12_000) {
                while (true) {
                    when (evaluate("window.__multiwebChat.send()")) {
                        "dispatched" -> return@withTimeoutOrNull true
                        "send_not_ready", "input_not_ready" -> delay(200)
                        else -> throw WebAutomationException("send_dispatch_failed", "网页发送状态异常，未自动重发")
                    }
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false
            if (!dispatched) {
                if (!nativeEnter || evaluate("window.__multiwebChat.nativeEnter()") != "true") {
                    throw WebAutomationException("send_button_not_ready", "网页发送按钮未就绪，未发送请求")
                }
                withContext(Dispatchers.Main.immediate) {
                    view.requestFocus()
                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
            webStageTimeout(15_000, "submission_unconfirmed", "网页未确认接收，本次未自动重发；请先检查网页再重试") {
                while (true) {
                    val state = next()
                    if (state.acknowledged) {
                        // Fast replies may already contain text in the receipt. Do not discard it.
                        receipt = state
                        break
                    }
                }
            }
            call.received()
        }

        suspend fun reply(onUpdate: suspend (WebChatSnapshot) -> Unit): WebChatSnapshot {
            val progress = WebChatProgress()
            var published = WebChatSnapshot()
            return webStageTimeout(options.replyTimeoutMs, "chat_timeout", "网页完整回复等待超时，本次未自动重发") {
                while (true) {
                    val current = receipt?.also { receipt = null } ?: next()
                    if (current.acknowledged && (current.text.isNotBlank() || current.images.isNotEmpty())) {
                        call.output()
                        if (current.text != published.text || current.images != published.images) {
                            onUpdate(current)
                            published = current
                        }
                    }
                    if (progress.accept(current, System.nanoTime() / 1_000_000)) {
                        verifyModel(current.pageModel)
                        return@webStageTimeout current
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("网页没有完整回答")
            }
        }

        private suspend fun next(): WebChatSnapshot {
            // Events carry normal progress; bounded native reads also cover missing DOM events.
            val raw = withTimeoutOrNull(500) { events.receive() }
            return if (raw == null) snapshot() else parse(raw)
        }
    }

    private fun decode(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").get(0).toString() }.getOrDefault(raw)
    }
}

/** Own stage expiry becomes an actionable failure; parent cancellation stays cancellation. */
internal suspend fun <T : Any> webStageTimeout(
    timeoutMs: Long, code: String, message: String, block: suspend () -> T,
): T = withTimeoutOrNull(timeoutMs) { block() }
    ?: throw WebAutomationException(code, message)
