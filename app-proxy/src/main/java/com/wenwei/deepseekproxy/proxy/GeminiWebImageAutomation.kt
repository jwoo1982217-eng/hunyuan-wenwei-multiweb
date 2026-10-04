package com.wenwei.deepseekproxy.proxy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.floor

internal data class ImageCropBounds(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun scaledImageCrop(
    left: Double,
    top: Double,
    width: Double,
    height: Double,
    viewportWidth: Double,
    viewportHeight: Double,
    bitmapWidth: Int,
    bitmapHeight: Int
): ImageCropBounds? {
    if (width <= 1.0 || height <= 1.0 || viewportWidth <= 1.0 || viewportHeight <= 1.0 ||
        bitmapWidth <= 1 || bitmapHeight <= 1
    ) return null
    val scaleX = bitmapWidth / viewportWidth
    val scaleY = bitmapHeight / viewportHeight
    val x1 = floor(left * scaleX).toInt().coerceIn(0, bitmapWidth - 1)
    val y1 = floor(top * scaleY).toInt().coerceIn(0, bitmapHeight - 1)
    val x2 = ceil((left + width) * scaleX).toInt().coerceIn(x1 + 1, bitmapWidth)
    val y2 = ceil((top + height) * scaleY).toInt().coerceIn(y1 + 1, bitmapHeight)
    return ImageCropBounds(x1, y1, x2 - x1, y2 - y1)
}

internal fun buildGeminiImageInstruction(prompt: String, ratio: String?): String = buildString {
    append("用户原始画面描述：").append(prompt.trim()).append("。")
    ratio?.takeIf(String::isNotBlank)?.let { append("目标画面比例：").append(it).append("。") }
    append("请严格遵循上述原始描述，使用你的图片生成工具创建图片，不要只回复文字。")
}

/** Generates images through the user's visible, signed-in Gemini WebView. */
object GeminiWebImageAutomation {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var attached: WebView? = null

    fun attach(webView: WebView) {
        attached = webView
        LogStore.i("GeminiImage", "Gemini 生图 WebView 已连接")
    }

    fun detach(webView: WebView) {
        if (attached === webView) attached = null
    }

    fun isAttached(): Boolean = attached != null

    suspend fun generateImage(prompt: String, ratio: String?): List<String> = BrowserRelayLane.run("gemini-image-web") relay@ {
        require(prompt.isNotBlank()) { "prompt不能为空" }
        val webView = BrowserRelayActivation.activateAndAwait("gemini-image-web") { attached }
            ?: throw WebAutomationException(
                "webview_not_ready", "请先在浏览器页打开 Gemini Web 并保持 App 运行"
            )
        if (!evaluate(webView, "location.href").contains("gemini.google.com", true)) {
            throw WebAutomationException("webview_not_ready", "Gemini 网页未打开或登录已失效")
        }

        waitForEditor(webView, 25_000)
        val baseline = imageUrls(jsonResult(evaluate(webView, IMAGE_STATE_SCRIPT))).toSet()
        val instruction = buildGeminiImageInstruction(prompt, ratio)
        val encoded = JSONObject.quote(instruction)
        val filled = jsonResult(evaluate(webView, """
            (() => {
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
              return JSON.stringify({ok:true,text:(editor.innerText||editor.value||editor.textContent||'').trim()});
            })()
        """.trimIndent()))
        if (!filled.optBoolean("ok")) throw WebAutomationException(
            "prompt_editor_not_found", "Gemini 网页提示词输入框尚未就绪"
        )
        val editorText = filled.optString("text")
        if (!editorText.contains(prompt.trim(), ignoreCase = true)) {
            throw WebAutomationException(
                "prompt_fill_mismatch",
                "Gemini 网页提示词写入不完整，已停止发送（期望：${prompt.take(60)}；实际：${editorText.take(100)}）"
            )
        }

        val sendDeadline = System.currentTimeMillis() + 5_000
        var sent = false
        while (!sent && System.currentTimeMillis() < sendDeadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            sent = jsonResult(evaluate(webView, SEND_SCRIPT)).optBoolean("clicked")
            if (!sent) delay(250)
        }
        if (!sent) {
            LogStore.w("GeminiImage", "发送按钮未出现，改用 WebView 原生 Enter 提交")
            pressEnter(webView)
            delay(1_000)
            sent = jsonResult(evaluate(webView, """
                (() => {
                  const editor=document.querySelector('rich-textarea .ql-editor[contenteditable="true"],.ql-editor[contenteditable="true"],div[contenteditable="true"][role="textbox"],textarea');
                  const text=(editor?.innerText||editor?.value||'').trim();
                  const busy=!![...document.querySelectorAll('button')].find(b=>/stop response|停止生成|停止回复/i.test((b.getAttribute('aria-label')||b.title||b.innerText||'').trim()));
                  return JSON.stringify({clicked:busy||!text});
                })()
            """.trimIndent())).optBoolean("clicked")
        }
        if (!sent) throw WebAutomationException("send_button_not_ready", "Gemini 网页发送按钮不可用，原生 Enter 也未提交")

        val deadline = System.currentTimeMillis() + 300_000
        var lastUrls = emptyList<String>()
        var stablePolls = 0
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            delay(800)
            val state = jsonResult(evaluate(webView, IMAGE_STATE_SCRIPT))
            val urls = imageUrls(state).filterNot(baseline::contains)
            stablePolls = if (urls.isNotEmpty() && urls == lastUrls) stablePolls + 1 else 0
            lastUrls = urls
            if (urls.isNotEmpty() && (!state.optBoolean("busy") || stablePolls >= 2)) {
                return@relay urls.map { materializeUrl(webView, it) }
            }
            val pageError = state.optString("error")
            if (pageError.isNotBlank() && !state.optBoolean("busy")) {
                throw WebAutomationException("upstream_page_error", pageError)
            }
        }
        throw WebAutomationException("generation_timeout", "Gemini 网页生图等待超过 300 秒")
    }

    private suspend fun waitForEditor(webView: WebView, timeout: Long) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            if (evaluate(webView, EDITOR_EXISTS_SCRIPT).toBoolean()) return
            delay(300)
        }
        throw WebAutomationException("prompt_editor_not_found", "Gemini 页面加载超时，请在浏览器页刷新")
    }

    internal suspend fun materializeUrl(webView: WebView, url: String): String {
        if (!url.startsWith("blob:", ignoreCase = true)) return url
        evaluate(webView, """
            (() => {
              window.__multiwebGeminiImageData = null;
              const target=${JSONObject.quote(url)};
              const image=[...document.images].find(img => (img.currentSrc||img.src)===target);
              const fromRenderedImage=() => new Promise((resolve,reject) => {
                if(!image||!image.complete||!image.naturalWidth) return reject(new Error('rendered image not ready'));
                try {
                  const canvas=document.createElement('canvas');
                  canvas.width=image.naturalWidth; canvas.height=image.naturalHeight;
                  canvas.getContext('2d').drawImage(image,0,0);
                  resolve(canvas.toDataURL('image/png'));
                } catch(e) { reject(e); }
              });
              const fromBlobFetch=() => fetch(target).then(r => r.blob()).then(blob => new Promise((resolve,reject) => {
                const reader=new FileReader(); reader.onload=()=>resolve(reader.result); reader.onerror=()=>reject(reader.error); reader.readAsDataURL(blob);
              }));
              fromRenderedImage().catch(() => fromBlobFetch())
                .then(data => { window.__multiwebGeminiImageData=String(data); })
                .catch(e => { window.__multiwebGeminiImageData='ERROR:'+String(e); });
              return 'started';
            })()
        """.trimIndent())

        val deadline = System.currentTimeMillis() + 30_000
        var length = -1
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            val raw = evaluate(webView, "typeof window.__multiwebGeminiImageData==='string' ? window.__multiwebGeminiImageData.length : -1")
            length = raw.toIntOrNull() ?: -1
            if (length >= 0) break
            delay(200)
        }
        if (length <= 0) throw WebAutomationException("image_export_failed", "Gemini 图片导出超时")
        val builder = StringBuilder(length)
        var offset = 0
        while (offset < length) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            val end = minOf(offset + 128_000, length)
            builder.append(evaluate(webView, "window.__multiwebGeminiImageData.slice($offset,$end)"))
            offset = end
        }
        evaluate(webView, "window.__multiwebGeminiImageData=null; true")
        val dataUrl = builder.toString()
        if (dataUrl.startsWith("ERROR:")) {
            LogStore.w("GeminiImage", "网页数据导出失败，改用原生渲染兜底：${dataUrl.take(160)}")
            return captureRenderedImage(webView, url)
        }
        return GeneratedImageStore.saveDataUrl(dataUrl)
    }

    /**
     * Gemini sometimes renders a blob owned by an isolated frame. The top page can display it,
     * but both canvas.toDataURL() and fetch(blob:) are then blocked. Capture only that already
     * rendered image from WebView as a provider-local fallback; other providers keep their paths.
     */
    private suspend fun captureRenderedImage(webView: WebView, url: String): String {
        val target = JSONObject.quote(url)
        val prepared = jsonResult(evaluate(webView, """
            (() => {
              const target=$target;
              const image=[...document.images].find(img => (img.currentSrc||img.src)===target);
              if(!image) return JSON.stringify({ok:false,error:'rendered_image_not_found'});
              document.getElementById('__multiweb_image_capture_overlay')?.remove();
              const overlay=document.createElement('div');
              overlay.id='__multiweb_image_capture_overlay';
              Object.assign(overlay.style,{position:'fixed',inset:'0',zIndex:'2147483647',background:'#000',
                display:'flex',alignItems:'center',justifyContent:'center',pointerEvents:'none'});
              const clone=document.createElement('img');
              clone.id='__multiweb_image_capture_target';
              clone.src=image.currentSrc||image.src;
              clone.alt='';
              Object.assign(clone.style,{display:'block',maxWidth:'100vw',maxHeight:'100vh',width:'auto',height:'auto',
                objectFit:'contain',margin:'0',padding:'0',border:'0'});
              overlay.appendChild(clone);
              document.documentElement.appendChild(overlay);
              return JSON.stringify({ok:true});
            })()
        """.trimIndent()))
        if (!prepared.optBoolean("ok")) throw WebAutomationException(
            "image_export_failed", "Gemini 已生图，但未找到可导出的渲染图片"
        )
        return try {
            val deadline = System.currentTimeMillis() + 10_000
            var metrics = JSONObject()
            while (System.currentTimeMillis() < deadline && !metrics.optBoolean("ok")) {
                delay(200)
                metrics = jsonResult(evaluate(webView, """
                    (() => {
                      const image=document.getElementById('__multiweb_image_capture_target');
                      if(!image||!image.complete||!image.naturalWidth) return JSON.stringify({ok:false});
                      const r=image.getBoundingClientRect();
                      return JSON.stringify({ok:r.width>1&&r.height>1,left:r.left,top:r.top,width:r.width,height:r.height,
                        viewportWidth:window.innerWidth,viewportHeight:window.innerHeight});
                    })()
                """.trimIndent()))
            }
            if (!metrics.optBoolean("ok")) throw WebAutomationException(
                "image_export_failed", "Gemini 已生图，但独立渲染图片尚未就绪"
            )
            val bitmap = captureWebViewCrop(webView, metrics) ?: throw WebAutomationException(
                "image_export_failed", "Gemini 已生图，但独立渲染图片区域无效"
            )
            try {
                withContext(Dispatchers.IO) { GeneratedImageStore.saveBitmap(bitmap) }.also {
                    LogStore.i("GeminiImage", "已通过独立渲染层保存 Gemini 图片：$it")
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            evaluate(webView, "document.getElementById('__multiweb_image_capture_overlay')?.remove(); true")
        }
    }

    private suspend fun captureWebViewCrop(webView: WebView, metrics: JSONObject): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            main.post {
                if (!continuation.isActive) return@post
                val viewWidth = webView.width
                val viewHeight = webView.height
                val bounds = scaledImageCrop(
                    metrics.optDouble("left"), metrics.optDouble("top"),
                    metrics.optDouble("width"), metrics.optDouble("height"),
                    metrics.optDouble("viewportWidth"), metrics.optDouble("viewportHeight"),
                    viewWidth, viewHeight
                )
                if (bounds == null) {
                    continuation.resume(null)
                    return@post
                }
                val full = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
                webView.draw(Canvas(full))
                val cropped = Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width, bounds.height)
                full.recycle()
                continuation.resume(cropped)
            }
        }

    private suspend fun pressEnter(webView: WebView) = suspendCancellableCoroutine { continuation ->
        main.post {
            if (!continuation.isActive) return@post
            webView.requestFocus()
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            continuation.resume(Unit)
        }
    }

    private fun jsonResult(value: String): JSONObject =
        runCatching { JSONObject(value) }.getOrElse { JSONObject() }

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

    private const val EDITOR_EXISTS_SCRIPT = """
        !!document.querySelector('rich-textarea .ql-editor[contenteditable="true"],.ql-editor[contenteditable="true"],div[contenteditable="true"][role="textbox"],textarea')
    """

    private const val SEND_SCRIPT = """
        (() => {
          const buttons=[...document.querySelectorAll('button')];
          const send=document.querySelector('button.send-button,button[aria-label*="Send message"],button[aria-label*="发送消息"],button[data-test-id="send-button"]')||
            buttons.find(b=>/^(Send|发送)$|send message|发送消息/i.test((b.getAttribute('aria-label')||b.title||b.innerText||'').trim()));
          if(!send||send.disabled||send.getAttribute('aria-disabled')==='true') return JSON.stringify({clicked:false});
          send.click(); return JSON.stringify({clicked:true});
        })()
    """

    private const val IMAGE_STATE_SCRIPT = """
        (() => {
          const candidates=[...document.querySelectorAll('model-response img,.model-response-text img,[data-test-id="model-response"] img,message-content img,.response-container-content img')];
          const urls=candidates.filter(i =>
              (i.naturalWidth>=256&&i.naturalHeight>=256) ||
              /googleusercontent|ggpht|blob:|generated/i.test(i.currentSrc||i.src||''))
            .map(i=>i.currentSrc||i.src).filter(Boolean);
          const buttons=[...document.querySelectorAll('button')];
          const busy=!!buttons.find(b=>/stop response|停止生成|停止回复/i.test((b.getAttribute('aria-label')||b.title||b.innerText||'').trim()))||
            !!document.querySelector('mat-progress-spinner,.loading-indicator');
          const latest=[...document.querySelectorAll('model-response,.model-response-text,[data-test-id="model-response"],message-content,.response-container-content')].pop();
          const text=(latest?.innerText||latest?.textContent||'').trim();
          const error=/无法生成|不能生成|generation failed|could not generate|try again later/i.test(text) ? text.slice(0,220) : '';
          return JSON.stringify({urls:[...new Set(urls)],busy,error});
        })()
    """
}
