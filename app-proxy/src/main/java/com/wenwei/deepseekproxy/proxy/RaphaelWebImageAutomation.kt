package com.wenwei.deepseekproxy.proxy

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

/** Automates Raphael's public, no-login Basic image generator in a visible WebView. */
object RaphaelWebImageAutomation {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()
    @Volatile private var attached: WebView? = null

    fun attach(view: WebView) {
        attached = view
        LogStore.i("RaphaelImage", "Raphael 免费生图 WebView 已连接")
    }

    fun isAttached(view: WebView): Boolean = attached === view
    fun isAttached(): Boolean = attached != null

    fun detach(view: WebView) {
        if (attached === view) attached = null
    }

    suspend fun generateImage(prompt: String, ratio: String?): List<String> = lock.withLock {
        require(prompt.isNotBlank()) { "prompt不能为空" }
        val view = BrowserRelayActivation.activateAndAwait("raphael-image-web") { attached }
            ?: throw WebAutomationException(
                "webview_not_ready", "请先在浏览器页打开 Raphael Web 并保持 App 运行"
            )
        if (!evaluate(view, "location.hostname").contains("raphael.app", true)) {
            throw WebAutomationException("webview_not_ready", "Raphael 网页尚未打开")
        }
        waitForComposer(view, 30_000)
        LogStore.i("RaphaelImage", "提示词输入区已就绪，准备提交生成")
        val baseline = imageUrls(state(evaluate(view, IMAGE_STATE_SCRIPT))).toSet()
        val encodedPrompt = JSONObject.quote(prompt.trim())
        val targetRatio = normalizeRatio(ratio)
        val prepared = state(evaluate(view, """
            (() => {
              const input=[...document.querySelectorAll('textarea,input[type="text"],[contenteditable="true"]')]
                .find(e=>{ const p=(e.placeholder||e.getAttribute('aria-label')||'').toLowerCase(); return /prompt|描述|想象|创作/.test(p); })
                ||document.querySelector('textarea');
              if(!input) return JSON.stringify({ok:false,error:'prompt_editor_not_found'});
              input.focus();
              if(input.tagName==='TEXTAREA'||input.tagName==='INPUT') {
                const setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(input),'value')?.set;
                if(setter) setter.call(input,$encodedPrompt); else input.value=$encodedPrompt;
              } else input.textContent=$encodedPrompt;
              input.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,inputType:'insertText',data:$encodedPrompt}));
              input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:$encodedPrompt}));
              input.dispatchEvent(new Event('change',{bubbles:true}));
              const ratio=${JSONObject.quote(targetRatio)};
              const visible=e=>!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
              const exact=[...document.querySelectorAll('button,[role="button"],[role="option"]')]
                .filter(visible).find(e=>{
                  const text=(e.innerText||e.textContent||'').trim();
                  return text===ratio||text.startsWith(ratio+' ')||text.startsWith(ratio+'\n');
                });
              if(exact) exact.click();
              window.__multiwebRaphaelPrompt=$encodedPrompt;
              return JSON.stringify({ok:true,ratioClicked:!!exact});
            })()
        """.trimIndent()))
        if (!prepared.optBoolean("ok")) throw WebAutomationException(
            "prompt_editor_not_found", "Raphael 网页提示词输入框尚未就绪"
        )
        if (!prepared.optBoolean("ratioClicked")) {
            LogStore.w("RaphaelImage", "未找到比例 $targetRatio，网页将使用当前比例")
        }

        val sendDeadline = System.currentTimeMillis() + 20_000
        var sent = false
        while (!sent && System.currentTimeMillis() < sendDeadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            sent = state(evaluate(view, SEND_SCRIPT)).optBoolean("clicked")
            if (!sent) delay(300)
        }
        if (!sent) throw WebAutomationException("send_button_not_ready", "Raphael 网页生成按钮不可用")
        LogStore.i("RaphaelImage", "已点击生成，等待 Raphael 免费队列")

        val deadline = System.currentTimeMillis() + 180_000
        var lastUrls = emptyList<String>()
        var stable = 0
        var lastProgressLog = 0L
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            delay(1_000)
            val currentState = state(evaluate(view, IMAGE_STATE_SCRIPT))
            val urls = imageUrls(currentState).filterNot(baseline::contains)
            stable = if (urls.isNotEmpty() && urls == lastUrls) stable + 1 else 0
            lastUrls = urls
            if (urls.isNotEmpty() && (!currentState.optBoolean("busy") || stable >= 3)) {
                LogStore.i("RaphaelImage", "生成完成，提取到 ${urls.size} 张图片")
                return@withLock urls.take(4)
            }
            if (currentState.optBoolean("busy") && System.currentTimeMillis() - lastProgressLog >= 15_000) {
                LogStore.i("RaphaelImage", "仍在生成/排队，已等待 ${((180_000 - (deadline - System.currentTimeMillis())) / 1000)} 秒")
                lastProgressLog = System.currentTimeMillis()
            }
            currentState.optString("error").takeIf(String::isNotBlank)?.let {
                if (!currentState.optBoolean("busy")) throw WebAutomationException("upstream_page_error", it)
            }
        }
        throw WebAutomationException("generation_timeout", "Raphael 免费队列等待超过 180 秒，请查看网页是否仍在生成")
    }

    private fun normalizeRatio(value: String?): String {
        val supported = setOf("1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9", "4:5", "5:4")
        return value?.takeIf(supported::contains) ?: "1:1"
    }

    private suspend fun waitForComposer(view: WebView, timeout: Long) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw CancellationException()
            if (evaluate(view, EDITOR_EXISTS_SCRIPT).toBoolean()) return
            delay(300)
        }
        throw WebAutomationException("prompt_editor_not_found", "Raphael 页面加载超时，请在浏览器页刷新")
    }

    private fun state(value: String): JSONObject = runCatching { JSONObject(value) }.getOrElse { JSONObject() }

    private fun imageUrls(value: JSONObject): List<String> = value.optJSONArray("urls")?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.orEmpty()

    private suspend fun evaluate(view: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            main.post {
                if (!continuation.isActive) return@post
                view.evaluateJavascript(script) { encoded ->
                    if (continuation.isActive) continuation.resume(decode(encoded))
                }
            }
        }

    private fun decode(encoded: String?): String {
        if (encoded == null || encoded == "null") return ""
        return runCatching { JSONArray("[$encoded]").getString(0) }.getOrDefault(encoded)
    }

    private const val EDITOR_EXISTS_SCRIPT = """
        [...document.querySelectorAll('textarea,input[type="text"],[contenteditable="true"]')].some(e => {
          const p=(e.placeholder||e.getAttribute('aria-label')||'').toLowerCase();
          return /prompt|描述|想象|创作/.test(p);
        }) || !!document.querySelector('textarea')
    """

    private const val SEND_SCRIPT = """
        (() => {
          const visible=e=>!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
          const button=[...document.querySelectorAll('button,[role="button"]')].filter(visible).find(e=>{
            const text=(e.innerText||e.textContent||e.getAttribute('aria-label')||'').trim();
            return /^(生成|Generate)(\s|$)/i.test(text);
          });
          if(!button||button.disabled||button.getAttribute('aria-disabled')==='true') return JSON.stringify({clicked:false});
          button.click(); return JSON.stringify({clicked:true});
        })()
    """

    private const val IMAGE_STATE_SCRIPT = """
        (() => {
          const visible=e=>!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
          const urls=[...document.images].filter(visible).filter(img=>{
            const src=img.currentSrc||img.src||'';
            if(!src||/logo|favicon|avatar|app-tools|templates|inspiration/i.test(src)) return false;
            return img.naturalWidth>=384&&img.naturalHeight>=384;
          }).map(img=>img.currentSrc||img.src).filter(Boolean);
          const pageText=(document.body?.innerText||'').slice(-3000);
          const busy=/正在生成|排队中|Generating|Queued|Processing/i.test(pageText)&&
            !/生成完成|Generation complete/i.test(pageText.slice(-500));
          const errorMatch=pageText.match(/(生成失败[^\n]*|Generation failed[^\n]*|请求过于频繁[^\n]*|Daily limit[^\n]*)/i);
          return JSON.stringify({urls:[...new Set(urls)],busy,error:errorMatch?.[1]||''});
        })()
    """
}
