package com.github.jing332.deepseekproxy.proxy

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
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

/** Automates ordinary Gemini web chat in the user's visible, signed-in WebView. */
data class GeminiWebChatResult(val text: String, val images: List<String> = emptyList())

object GeminiWebChatAutomation {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()
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
        onUpdate: suspend (GeminiWebChatResult) -> Unit = {}
    ): GeminiWebChatResult = lock.withLock {
        val view = BrowserRelayActivation.activateAndAwait("gemini-web") { webView }
            ?: throw WebAutomationException(
                "webview_not_ready", "请先在浏览器页打开 Gemini 并保持 App 运行"
            )
        var currentHref = evaluate(view, "location.href")
        if (!currentHref.contains("gemini.google.com", true) &&
            view.url.orEmpty().contains("gemini.google.com", true)
        ) {
            reload(view)
            delay(1_500)
            currentHref = evaluate(view, "location.href")
        }
        if (!currentHref.contains("gemini.google.com", true)) {
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
        uploadAttachments(view, messages.flatMap(ChatMessage::attachments))
        val baselineState = result(evaluate(view, RESPONSE_STATE_SCRIPT))
        val baselineTexts = responseSnapshots(baselineState).map(GeminiResponseSnapshot::text).toSet()
        val baselineImages = responseSnapshots(baselineState).flatMap(GeminiResponseSnapshot::images).toSet()
        val encoded = JSONObject.quote(prompt)
        val filled = result(evaluate(view, """
            (() => { try {
              window.__multiwebGeminiEnterAttempted=false;
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

        val sendDeadline = System.currentTimeMillis() + 15_000
        var sent = false
        while (!sent && System.currentTimeMillis() < sendDeadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            sent = result(evaluate(view, SEND_SCRIPT)).optBoolean("clicked")
            if (!sent) delay(250)
        }
        if (!sent) {
            LogStore.w("GeminiChat", "发送按钮未出现，改用 WebView 原生 Enter 提交")
            pressEnter(view)
            delay(1_000)
            sent = result(evaluate(view, SUBMISSION_STATE_SCRIPT)).optBoolean("submitted")
        }
        if (!sent) throw WebAutomationException(
            "send_button_not_ready", "Gemini 网页发送按钮不可用，原生 Enter 也未提交"
        )

        val deadline = System.currentTimeMillis() + 180_000
        var last = GeminiResponseSnapshot()
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            delay(200)
            val state = result(evaluate(view, RESPONSE_STATE_SCRIPT))
            val current = responseSnapshots(state).lastOrNull { snapshot ->
                snapshot.text.isNotBlank() && snapshot.text !in baselineTexts ||
                    snapshot.images.any { it !in baselineImages }
            } ?: GeminiResponseSnapshot()
            stable = if ((current.text.isNotBlank() || current.images.isNotEmpty()) && current == last) stable + 1 else 0
            if (current.text.isNotBlank() && current.text != last.text) {
                onUpdate(GeminiWebChatResult(current.text))
            }
            last = current
            if ((current.text.isNotBlank() || current.images.isNotEmpty()) && !state.optBoolean("busy") && stable >= 3) {
                val images = current.images.filterNot(baselineImages::contains).map { url ->
                    GeminiWebImageAutomation.materializeUrl(view, url)
                }
                val completed = GeminiWebChatResult(current.text, images)
                onUpdate(completed)
                return@withLock completed
            }
        }
        throw WebAutomationException("chat_timeout", "Gemini 网页回复等待超时")
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

    private data class GeminiResponseSnapshot(
        val text: String = "",
        val images: List<String> = emptyList()
    )

    private fun responseSnapshots(state: JSONObject): List<GeminiResponseSnapshot> {
        val array = state.optJSONArray("texts") ?: JSONArray()
        val imageGroups = state.optJSONArray("images") ?: JSONArray()
        return (0 until maxOf(array.length(), imageGroups.length())).mapNotNull { index ->
            val text = array.optString(index).trim()
            val images = imageGroups.optJSONArray(index)?.let { urls ->
                (0 until urls.length()).mapNotNull { imageIndex ->
                    urls.optString(imageIndex).takeIf(String::isNotBlank)
                }
            }.orEmpty()
            GeminiResponseSnapshot(text, images).takeIf { text.isNotBlank() || images.isNotEmpty() }
        }
    }

    private fun result(value: String): JSONObject =
        runCatching { JSONObject(value) }.getOrElse { JSONObject() }

    private suspend fun evaluate(view: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            main.post {
                if (!cont.isActive) return@post
                view.evaluateJavascript(script) { raw ->
                    if (cont.isActive) cont.resume(decode(raw))
                }
            }
        }

    private suspend fun pressEnter(view: WebView) = suspendCancellableCoroutine { cont ->
        main.post {
            if (!cont.isActive) return@post
            view.requestFocus()
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            cont.resume(Unit)
        }
    }

    private fun decode(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault(raw)
    }

    private const val EDITOR_EXISTS_SCRIPT = """
        !!document.querySelector('rich-textarea .ql-editor[contenteditable="true"],.ql-editor[contenteditable="true"],div[contenteditable="true"][role="textbox"],textarea')
    """

    private const val SEND_SCRIPT = """
        (() => {
          const buttons=[...document.querySelectorAll('button')];
          const send=document.querySelector('button.send-button,button[aria-label*="Send" i],button[aria-label*="发送"],button[data-test-id="send-button"],button[data-testid="send-button"]')||
            buttons.find(b=>/^(Send|发送)$|send message|发送消息|^send$/i.test((b.getAttribute('aria-label')||b.title||b.innerText||b.textContent||'').trim()))||
            buttons.find(b=>/^(send|arrow_upward)$/i.test((b.querySelector('mat-icon')?.textContent||'').trim()));
          if(send&&!send.disabled&&send.getAttribute('aria-disabled')!=='true') {
            send.click(); return JSON.stringify({clicked:true,method:'button'});
          }
          const editor=document.querySelector('rich-textarea .ql-editor[contenteditable="true"],.ql-editor[contenteditable="true"],div[contenteditable="true"][role="textbox"],textarea');
          if(editor&&!window.__multiwebGeminiEnterAttempted) {
            window.__multiwebGeminiEnterAttempted=true;
            const init={bubbles:true,cancelable:true,key:'Enter',code:'Enter',keyCode:13,which:13};
            editor.dispatchEvent(new KeyboardEvent('keydown',init));
            editor.dispatchEvent(new KeyboardEvent('keypress',init));
            editor.dispatchEvent(new KeyboardEvent('keyup',init));
          }
          return JSON.stringify({clicked:false});
        })()
    """

    private const val SUBMISSION_STATE_SCRIPT = """
        (() => {
          const editor=document.querySelector('rich-textarea .ql-editor[contenteditable="true"],.ql-editor[contenteditable="true"],div[contenteditable="true"][role="textbox"],textarea');
          const text=(editor?.innerText||editor?.value||'').trim();
          const busy=!![...document.querySelectorAll('button')].find(b=>/stop response|停止生成|停止回复/i.test((b.getAttribute('aria-label')||b.title||b.innerText||'').trim()));
          return JSON.stringify({submitted:busy||!text});
        })()
    """

    private const val RESPONSE_STATE_SCRIPT = """
        (() => {
          const responses=[...document.querySelectorAll('model-response')];
          const texts=responses.map(root=>{
            const nodes=[...root.querySelectorAll('structured-content-container.model-response-text,message-content,.markdown.markdown-main-panel')];
            const candidates=nodes
              .map(node=>(node.innerText||node.textContent||'').trim())
              .filter(Boolean)
              .sort((left,right)=>right.length-left.length);
            return candidates[0]||'';
          });
          const images=responses.map(root=>[...new Set([...root.querySelectorAll('img')]
            .filter(image=>(image.naturalWidth>=256&&image.naturalHeight>=256)||/googleusercontent|ggpht|blob:|generated/i.test(image.currentSrc||image.src||''))
            .map(image=>image.currentSrc||image.src).filter(Boolean))]);
          const latest=responses[responses.length-1];
          const visible=node=>!!node&&(node.offsetWidth>0||node.offsetHeight>0||node.getClientRects().length>0);
          const busy=!![...document.querySelectorAll('button')].find(b=>visible(b)&&/stop response|停止生成|停止回复/i.test((b.getAttribute('aria-label')||b.title||b.innerText||'').trim()))||
            !!latest&&[...latest.querySelectorAll('mat-progress-spinner,.loading-indicator')].some(visible);
          return JSON.stringify({texts,images,busy});
        })()
    """
}
