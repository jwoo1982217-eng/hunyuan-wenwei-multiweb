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

/** Drives the normal signed-in ChatGPT web UI without bypassing verification. */
object ChatGptWebAutomation {
    private val main = Handler(Looper.getMainLooper())
    private val requestLock = Mutex()
    @Volatile private var attached: WebView? = null

    fun attach(webView: WebView) {
        attached = webView
        LogStore.i("ChatGPTImage", "ChatGPT WebView 已连接")
    }

    fun detach(webView: WebView) {
        if (attached === webView) attached = null
    }

    fun isAttached(webView: WebView): Boolean = attached === webView
    fun isAttached(): Boolean = attached != null

    suspend fun generateImage(prompt: String): List<String> = requestLock.withLock {
        require(prompt.isNotBlank()) { "prompt不能为空" }
        val webView = BrowserRelayActivation.activateAndAwait("gpt-image-2") { attached }
            ?: throw WebAutomationException("webview_not_ready", "请先在浏览器页打开 ChatGPT Web 并保持 App 运行")
        val currentUrl = evaluate(webView, "location.href")
        if (!currentUrl.contains("chatgpt.com", ignoreCase = true)) {
            throw WebAutomationException("webview_not_ready", "ChatGPT 网页未打开，请先进入浏览器页完成登录")
        }

        waitForEditor(webView, 30_000)
        val baseline = imageUrls(jsonResult(evaluate(webView, IMAGE_STATE_SCRIPT)))

        val start = evaluate(webView, """
            (() => {
              const text = e => (e.innerText || e.textContent || '').trim();
              const imageButton = [...document.querySelectorAll('button')]
                .find(b => text(b) === '生成图片');
              if (imageButton) {
                imageButton.click();
                return JSON.stringify({ok:true,mode:'entered'});
              }
              const alreadyImageMode = !!document.querySelector(
                'button[data-testid="good-image-turn-action-button"], button[data-testid="bad-image-turn-action-button"]'
              ) || [...document.images].some(i => /backend-api\/estuary|oaiusercontent/i.test(i.currentSrc || i.src || ''));
              if (alreadyImageMode && document.querySelector('#prompt-textarea[contenteditable="true"]')) {
                return JSON.stringify({ok:true,mode:'existing'});
              }
              if (document.querySelector('#prompt-textarea[contenteditable="true"]')) {
                return JSON.stringify({ok:true,mode:'direct'});
              }
              return JSON.stringify({ok:false,error:'image_button_not_found'});
            })()
        """.trimIndent())
        val startState = jsonResult(start)
        if (!startState.optBoolean("ok")) {
            throw WebAutomationException("image_button_not_found", "网页中未找到“生成图片”，请刷新 ChatGPT 后重试")
        }

        delay(500)
        val submittedPrompt = if (startState.optString("mode") == "direct") {
            "请使用图片生成工具创建图片，不要只回复文字：\n$prompt"
        } else {
            prompt
        }
        val encodedPrompt = JSONObject.quote(submittedPrompt)
        val filled = evaluate(webView, """
            (() => {
              const editor = document.querySelector('#prompt-textarea[contenteditable="true"], [contenteditable="true"]#prompt-textarea');
              if (!editor) return JSON.stringify({ok:false,error:'prompt_editor_not_found'});
              editor.focus();
              editor.replaceChildren();
              document.execCommand('insertText', false, $encodedPrompt);
              editor.dispatchEvent(new InputEvent('input', {bubbles:true,inputType:'insertText',data:$encodedPrompt}));
              return JSON.stringify({ok:true});
            })()
        """.trimIndent())
        if (!jsonResult(filled).optBoolean("ok")) {
            throw WebAutomationException("prompt_editor_not_found", "ChatGPT 网页提示词输入框尚未就绪")
        }

        // React enables/replaces the send button asynchronously after the input event.
        // Poll the live DOM instead of assuming it is ready after one fixed delay.
        val sendDeadline = System.currentTimeMillis() + 12_000L
        var sendState = JSONObject()
        while (System.currentTimeMillis() < sendDeadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            sendState = jsonResult(evaluate(webView, SEND_BUTTON_SCRIPT))
            if (sendState.optBoolean("clicked")) break
            delay(250)
        }
        if (!sendState.optBoolean("clicked")) {
            val detail = sendState.optString("detail").takeIf { it.isNotBlank() }
            throw WebAutomationException(
                "send_button_not_ready",
                "已填写提示词，但网页发送按钮仍不可用${detail?.let { "（$it）" }.orEmpty()}"
            )
        }

        val deadline = System.currentTimeMillis() + 280_000L
        var lastUrls = emptyList<String>()
        var stablePolls = 0
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            delay(800)
            val state = jsonResult(evaluate(webView, IMAGE_STATE_SCRIPT))
            val urls = imageUrls(state).filterNot(baseline::contains)
            val pageError = state.optString("error")
            if (pageError.isNotBlank()) throw WebAutomationException("upstream_page_error", pageError)
            stablePolls = if (urls.isNotEmpty() && urls == lastUrls) stablePolls + 1 else 0
            lastUrls = urls
            if (urls.isNotEmpty() && (!state.optBoolean("busy") || stablePolls >= 2)) {
                return@withLock urls.map { url ->
                    runCatching { materializeImage(webView, url) }.getOrElse { error ->
                        LogStore.w("ChatGPTImage", "图片本地缓存失败，保留网页地址：${error.message}")
                        url
                    }
                }
            }
        }
        throw WebAutomationException("generation_timeout", "ChatGPT 网页生图等待超过 280 秒")
    }

    private suspend fun materializeImage(webView: WebView, url: String): String {
        evaluate(webView, """
            (() => {
              window.__multiwebChatGptImageData=null;
              const target=${JSONObject.quote(url)};
              const image=[...document.images].find(img => (img.currentSrc||img.src)===target);
              const rendered=() => new Promise((resolve,reject) => {
                if(!image||!image.complete||!image.naturalWidth) return reject(new Error('rendered image not ready'));
                try {
                  const canvas=document.createElement('canvas');
                  canvas.width=image.naturalWidth; canvas.height=image.naturalHeight;
                  canvas.getContext('2d').drawImage(image,0,0);
                  resolve(canvas.toDataURL('image/png'));
                } catch(error) { reject(error); }
              });
              const fetched=() => fetch(target,{credentials:'include'})
                .then(response => {
                  if(!response.ok) throw new Error('HTTP '+response.status);
                  return response.blob();
                })
                .then(blob => new Promise((resolve,reject) => {
                  const reader=new FileReader();
                  reader.onload=()=>resolve(reader.result);
                  reader.onerror=()=>reject(reader.error);
                  reader.readAsDataURL(blob);
                }));
              rendered().catch(fetched)
                .then(data => { window.__multiwebChatGptImageData=String(data); })
                .catch(error => { window.__multiwebChatGptImageData='ERROR:'+String(error); });
              return 'started';
            })()
        """.trimIndent())

        val deadline = System.currentTimeMillis() + 30_000
        var length = -1
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            length = evaluate(
                webView,
                "typeof window.__multiwebChatGptImageData==='string' ? window.__multiwebChatGptImageData.length : -1"
            ).toIntOrNull() ?: -1
            if (length >= 0) break
            delay(200)
        }
        if (length <= 0) throw WebAutomationException("image_export_failed", "ChatGPT 图片导出超时")
        val data = StringBuilder(length)
        var offset = 0
        while (offset < length) {
            val end = minOf(offset + 128_000, length)
            data.append(evaluate(webView, "window.__multiwebChatGptImageData.slice($offset,$end)"))
            offset = end
        }
        evaluate(webView, "window.__multiwebChatGptImageData=null; true")
        val dataUrl = data.toString()
        if (dataUrl.startsWith("ERROR:")) {
            throw WebAutomationException("image_export_failed", dataUrl.take(180))
        }
        return GeneratedImageStore.saveDataUrl(dataUrl)
    }

    private suspend fun waitForEditor(webView: WebView, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            if (evaluate(
                    webView,
                    "!!document.querySelector('#prompt-textarea[contenteditable=\"true\"], [contenteditable=\"true\"]#prompt-textarea')"
                ).toBoolean()
            ) return
            delay(300)
        }
        throw WebAutomationException("prompt_editor_not_found", "ChatGPT 页面加载超时，请在浏览器页刷新")
    }

    private fun jsonResult(value: String): JSONObject = runCatching { JSONObject(value) }.getOrElse { JSONObject() }

    private fun imageUrls(state: JSONObject): List<String> = state.optJSONArray("urls")?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.orEmpty()

    private suspend fun evaluate(webView: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            main.post {
                if (!continuation.isActive) return@post
                webView.evaluateJavascript(script) { encoded ->
                    if (continuation.isActive) continuation.resume(decodeJavascriptValue(encoded))
                }
            }
        }

    private fun decodeJavascriptValue(encoded: String?): String {
        if (encoded == null || encoded == "null") return ""
        return runCatching { JSONArray("[$encoded]").getString(0) }.getOrDefault(encoded)
    }

    private const val IMAGE_STATE_SCRIPT = """
        (() => {
          // ChatGPT image turns may render their image gallery outside the assistant
          // article. Scan the full document; Kotlin filters the pre-request baseline.
          const urls = [...document.querySelectorAll('img')]
            .filter(i => (i.naturalWidth >= 256 && i.naturalHeight >= 256) ||
              /oaiusercontent|backend-api|estuary|blob:/i.test(i.currentSrc || i.src || ''))
            .map(i => i.currentSrc || i.src).filter(Boolean);
          const busy = !!document.querySelector('button[data-testid="stop-button"], button[aria-label*="停止"], button[aria-label*="Stop"]');
          const body = (document.body?.innerText || '').trim();
          return JSON.stringify({urls:[...new Set(urls)],busy,error:/出错|失败|error/i.test(body) && urls.length===0 ? body.slice(0,180) : ''});
        })()
    """

    private const val SEND_BUTTON_SCRIPT = """
        (() => {
          const editor = document.querySelector('#prompt-textarea[contenteditable="true"], [contenteditable="true"]#prompt-textarea');
          const buttons = [...document.querySelectorAll('button')];
          const send = document.querySelector(
            'button[data-testid="send-button"], button[data-testid="fruitjuice-send-button"], form button[type="submit"]'
          ) || buttons.find(b => {
            const label = (b.getAttribute('aria-label') || b.getAttribute('title') || b.innerText || '').trim();
            return /^(发送|Send)|发送提示|send prompt/i.test(label);
          }) || editor?.closest('form')?.querySelector('button:not([disabled])');
          if (!send) return JSON.stringify({clicked:false,detail:'未找到发送按钮'});
          const disabled = send.disabled || send.getAttribute('aria-disabled') === 'true';
          if (disabled) return JSON.stringify({clicked:false,detail:'按钮正在启用'});
          send.click();
          return JSON.stringify({clicked:true});
        })()
    """
}

class WebAutomationException(val code: String, message: String) : IllegalStateException(message)
