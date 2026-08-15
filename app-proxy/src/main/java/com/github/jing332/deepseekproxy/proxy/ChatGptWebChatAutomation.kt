package com.github.jing332.deepseekproxy.proxy

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** Automates ordinary ChatGPT web chat using the user's visible, signed-in WebView. */
object ChatGptWebChatAutomation {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()
    @Volatile private var webView: WebView? = null

    fun attach(view: WebView) {
        webView = view
        LogStore.i("ChatGPTChat", "ChatGPT 聊天 WebView 已连接")
    }

    fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    fun isAttached(): Boolean = webView != null

    suspend fun chat(
        messages: List<ChatMessage>,
        onUpdate: suspend (String) -> Unit = {},
    ): String = lock.withLock {
        val view = BrowserRelayActivation.activateAndAwait("chatgpt-web") { webView }
            ?: throw WebAutomationException(
            "webview_not_ready", "ChatGPT 网页仍在加载，请稍后重试"
        )
        if (!evaluate(view, "location.href").contains("chatgpt.com", true)) {
            throw WebAutomationException("webview_not_ready", "ChatGPT 网页登录无效")
        }
        waitForEditor(view, 30_000)

        // Image conversations keep the composer in image mode. Return to the normal
        // ChatGPT home page before starting a text conversation.
        val imageMode = evaluate(view, """
            !!document.querySelector('button[data-testid="good-image-turn-action-button"],button[data-testid="bad-image-turn-action-button"]')
        """.trimIndent()).toBoolean()
        if (imageMode) {
            load(view, "https://chatgpt.com/")
            // loadUrl() returns before Chromium replaces the current document. Without
            // this hand-off window the old image conversation's editor can satisfy the
            // readiness probe, and the text is then lost when navigation completes.
            delay(1_200)
            waitForEditor(view, 20_000)
        }

        val prompt = messagesToPrompt(messages)
        uploadAttachments(view, messages.flatMap(ChatMessage::attachments))
        val baseline = assistantTexts(evaluate(view, ASSISTANT_STATE_SCRIPT)).toSet()
        val encoded = JSONObject.quote(prompt)
        val filled = result(evaluate(view, """
            (() => {
              const editor = document.querySelector('#prompt-textarea[contenteditable="true"]');
              if (!editor) return JSON.stringify({ok:false,error:'prompt_editor_not_found'});
              editor.focus(); editor.replaceChildren();
              document.execCommand('insertText', false, $encoded);
              editor.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:$encoded}));
              return JSON.stringify({ok:true});
            })()
        """.trimIndent()))
        if (!filled.optBoolean("ok")) throw WebAutomationException(
            "prompt_editor_not_found", "ChatGPT 网页输入框未就绪"
        )

        val sendDeadline = System.currentTimeMillis() + 12_000
        var sent = false
        while (!sent && System.currentTimeMillis() < sendDeadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            sent = result(evaluate(view, SEND_SCRIPT)).optBoolean("clicked")
            if (!sent) delay(250)
        }
        if (!sent) throw WebAutomationException("send_button_not_ready", "ChatGPT 网页发送按钮不可用")

        val deadline = System.currentTimeMillis() + 180_000
        var last = ""
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            delay(200)
            val state = result(evaluate(view, ASSISTANT_STATE_SCRIPT))
            val candidates = assistantTexts(state).filterNot(baseline::contains)
            val current = candidates.lastOrNull().orEmpty()
            stable = if (current.isNotBlank() && current == last) stable + 1 else 0
            if (current.isNotBlank() && current != last) onUpdate(current)
            last = current
            if (current.isNotBlank() && !state.optBoolean("busy") && stable >= 3) return@withLock current
        }
        throw WebAutomationException("chat_timeout", "ChatGPT 网页回复等待超时")
    }

    private fun messagesToPrompt(messages: List<ChatMessage>): String {
        if (messages.size == 1 && messages[0].role == "user") return messages[0].textContent()
        return buildString {
            append("请根据以下完整对话上下文回答最后一个用户问题。\n\n")
            messages.forEach {
                val role = when (it.role) { "system" -> "系统"; "assistant" -> "助手"; else -> "用户" }
                append(role).append("：").append(it.textContent()).append("\n\n")
            }
        }.trim()
    }

    private suspend fun uploadAttachments(view: WebView, attachments: List<ChatAttachment>) {
        if (attachments.isEmpty()) return
        var state = result(evaluate(view, webAttachmentUploadScript(attachments)))
        if (!state.optBoolean("ok")) {
            evaluate(view, WEB_ATTACHMENT_BUTTON_SCRIPT)
            delay(600)
            state = result(evaluate(view, webAttachmentUploadScript(attachments)))
        }
        if (!state.optBoolean("ok")) throw WebAutomationException(
            "attachment_upload_not_ready", "ChatGPT 网页附件上传控件不可用"
        )
        delay(900)
    }

    private suspend fun waitForEditor(view: WebView, timeout: Long) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (evaluate(view, "!!document.querySelector('#prompt-textarea[contenteditable=\"true\"]')").toBoolean()) return
            delay(300)
        }
        throw WebAutomationException("prompt_editor_not_found", "ChatGPT 首页加载超时")
    }

    private fun assistantTexts(state: String): List<String> {
        return assistantTexts(result(state))
    }

    private fun assistantTexts(state: JSONObject): List<String> {
        val array = state.optJSONArray("texts") ?: JSONArray()
        return (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
    }

    private fun result(value: String): JSONObject = runCatching { JSONObject(value) }.getOrElse { JSONObject() }

    private suspend fun load(view: WebView, url: String) = suspendCancellableCoroutine { cont ->
        main.post { view.loadUrl(url); if (cont.isActive) cont.resume(Unit) }
    }

    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { cont ->
        main.post {
            if (!cont.isActive) return@post
            view.evaluateJavascript(script) { raw ->
                if (cont.isActive) cont.resume(decode(raw))
            }
        }
    }

    private fun decode(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault(raw)
    }

    private const val SEND_SCRIPT = """
        (() => {
          const editor=document.querySelector('#prompt-textarea[contenteditable="true"]');
          const buttons=[...document.querySelectorAll('button')];
          const send=document.querySelector('button[data-testid="send-button"],button[data-testid="fruitjuice-send-button"],form button[type="submit"]')||
            buttons.find(b=>/^(发送|Send)|发送提示|send prompt/i.test((b.getAttribute('aria-label')||b.title||b.innerText||'').trim()))||
            editor?.closest('form')?.querySelector('button:not([disabled])');
          if(!send||send.disabled||send.getAttribute('aria-disabled')==='true') return JSON.stringify({clicked:false});
          send.click(); return JSON.stringify({clicked:true});
        })()
    """

    private const val ASSISTANT_STATE_SCRIPT = """
        (() => {
          const texts=[...document.querySelectorAll('[data-message-author-role="assistant"]')]
            .map(n=>(n.innerText||n.textContent||'').trim()).filter(Boolean);
          const busy=!!document.querySelector('button[data-testid="stop-button"],button[aria-label*="停止"],button[aria-label*="Stop"]');
          return JSON.stringify({texts,busy});
        })()
    """
}
