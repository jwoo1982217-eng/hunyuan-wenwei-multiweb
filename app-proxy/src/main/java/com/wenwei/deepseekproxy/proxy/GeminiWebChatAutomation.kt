package com.wenwei.deepseekproxy.proxy

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** Automates ordinary Gemini web chat in the user's visible, signed-in WebView. */
data class GeminiWebChatResult(val text: String, val images: List<String> = emptyList())

object GeminiWebChatAutomation {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var webView: WebView? = null

    fun attach(view: WebView) {
        webView = view
        LogStore.i("GeminiChat", "Gemini 聊天 WebView 已连接")
    }

    fun isAttached(view: WebView): Boolean = webView === view
    fun isAttached(): Boolean = webView != null

    fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        options: WebChatOptions = WebChatOptions(),
        onUpdate: suspend (GeminiWebChatResult) -> Unit = {}
    ): GeminiWebChatResult = BrowserRelayLane.run("gemini-web") {
        val view = BrowserRelayActivation.activateAndAwait("gemini-web") { webView }
            ?: throw WebAutomationException(
                "webview_not_ready", "请先在浏览器页打开 Gemini 并保持 App 运行"
            )
        var currentHref = evaluate(view, "location.hostname")
        if (currentHref != "gemini.google.com" &&
            view.url.orEmpty().contains("gemini.google.com", true)
        ) {
            reload(view)
            delay(1_500)
            currentHref = evaluate(view, "location.hostname")
        }
        if (currentHref != "gemini.google.com") {
            val title = evaluate(view, "document.title")
            throw WebAutomationException(
                "webview_not_ready", "Gemini 网页未正常加载（$title，$currentHref），请检查网络后重试"
            )
        }

        if (!waitForEditor(view, 25_000)) {
            reload(view)
            if (!waitForEditor(view, 30_000)) {
            val href = evaluate(view, "location.href")
            val probe = evaluate(view, """
                JSON.stringify({ready:document.readyState,count:document.querySelectorAll('[contenteditable="true"],textarea').length,body:(document.body?.innerText||'').slice(0,80)})
            """.trimIndent())
            throw WebAutomationException(
                "prompt_editor_not_found",
                "Gemini 网页输入框未就绪（attached=${view.isAttachedToWindow}, url=$href, probe=$probe）"
            )
            }
        }

        val prompt = messagesToPrompt(messages)
        return@run BrowserChatRuntime.watch(view, true, prompt, options) { watch ->
            uploadAttachments(view, messages.flatMap(ChatMessage::attachments))
            val encoded = JSONObject.quote(prompt)
            val filled = result(evaluate(view, """
                (() => { try {
                  const editor=document.querySelector('rich-textarea .ql-editor[contenteditable="true"],.ql-editor[contenteditable="true"],div[contenteditable="true"][role="textbox"],textarea');
                  if(!editor) return JSON.stringify({ok:false,error:'prompt_editor_not_found'});
                  editor.focus();
                  if(typeof Quill==='function' && editor.classList.contains('ql-editor')) {
                    const quill=Quill.find(editor);
                    if(quill && typeof quill.setText==='function') {
                      quill.setText($encoded,'user');
                    } else {
                      editor.replaceChildren();
                      if(!document.execCommand('insertText',false,$encoded)) editor.textContent=$encoded;
                    }
                    editor.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:$encoded}));
                  } else if(editor.tagName==='TEXTAREA'||editor.tagName==='INPUT') {
                    const setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(editor),'value')?.set;
                    if(setter) setter.call(editor,$encoded); else editor.value=$encoded;
                    editor.dispatchEvent(new Event('input',{bubbles:true}));
                  } else {
                    editor.replaceChildren();
                    document.execCommand('insertText',false,$encoded);
                    editor.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:$encoded}));
                  }
                  return JSON.stringify({ok:true});
                } catch(e) { return JSON.stringify({ok:false,error:String(e)}); }
                })()
            """.trimIndent()))
            if (!filled.optBoolean("ok")) throw WebAutomationException(
                "prompt_fill_failed", "Gemini 网页输入失败：${filled.optString("error", "unknown")}"
            )

            watch.send(nativeEnter = true)
            val completed = watch.reply { state ->
                if (state.text.isNotBlank()) onUpdate(GeminiWebChatResult(state.text))
            }
            val images = completed.images.map { GeminiWebImageAutomation.materializeUrl(view, it) }
            GeminiWebChatResult(completed.text, images).also { onUpdate(it) }
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

    private suspend fun waitForEditor(view: WebView, timeout: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            if (evaluate(view, EDITOR_EXISTS_SCRIPT).toBoolean()) return true
            delay(300)
        }
        return false
    }

    private suspend fun reload(view: WebView) = suspendCancellableCoroutine { cont ->
        main.post {
            if (!cont.isActive) return@post
            view.reload()
            cont.resume(Unit)
        }
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
            "attachment_upload_not_ready", "Gemini 网页附件上传控件不可用"
        )
        delay(900)
    }

    private fun result(value: String): JSONObject =
        runCatching { JSONObject(value) }.getOrElse { JSONObject() }

    private suspend fun evaluate(view: WebView, script: String): String =
        BrowserChatRuntime.evaluate(view, script)

    private const val EDITOR_EXISTS_SCRIPT = """
        !!document.querySelector('rich-textarea .ql-editor[contenteditable="true"],.ql-editor[contenteditable="true"],div[contenteditable="true"][role="textbox"],textarea')
    """

}
