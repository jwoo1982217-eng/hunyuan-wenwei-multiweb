package com.wenwei.deepseekproxy.proxy

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** Automates ordinary ChatGPT web chat using the user's visible, signed-in WebView. */
object ChatGptWebChatAutomation {
    private val main = Handler(Looper.getMainLooper())
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
        options: WebChatOptions = WebChatOptions(),
        onUpdate: suspend (String) -> Unit = {},
    ): String = BrowserRelayLane.run("chatgpt-web") {
        val view = BrowserRelayActivation.activateAndAwait("chatgpt-web") { webView }
            ?: throw WebAutomationException(
            "webview_not_ready", "ChatGPT 网页仍在加载，请稍后重试"
        )
        if (evaluate(view, "location.hostname") != "chatgpt.com") {
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
        return@run BrowserChatRuntime.watch(view, false, prompt, options) { watch ->
            uploadAttachments(view, messages.flatMap(ChatMessage::attachments))
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

            watch.send()
            watch.reply { state ->
                if (state.text.isNotBlank()) onUpdate(state.text)
            }.text
        }
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

    private fun result(value: String): JSONObject = runCatching { JSONObject(value) }.getOrElse { JSONObject() }

    private suspend fun load(view: WebView, url: String) = suspendCancellableCoroutine { cont ->
        main.post { view.loadUrl(url); if (cont.isActive) cont.resume(Unit) }
    }

    private suspend fun evaluate(view: WebView, script: String): String =
        BrowserChatRuntime.evaluate(view, script)
}
