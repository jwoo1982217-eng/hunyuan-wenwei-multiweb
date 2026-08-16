package com.wenwei.deepseekproxy.proxy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import kotlin.coroutines.resume

data class CustomWebProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val modelId: String,
    val loginUrl: String,
    val inputSelector: String,
    val sendSelector: String,
    val responseSelector: String,
    val imageProvider: Boolean = false,
)

fun isKnownCustomWebImagePage(url: String): Boolean =
    url.contains("sps.yy-gp.com/hgyfa/", ignoreCase = true) ||
        url.contains("image.dddd.zone", ignoreCase = true)

fun isKnownCustomWebHybridImagePage(url: String): Boolean =
    url.contains("longcat.chat", ignoreCase = true)

fun customWebImageModelId(config: CustomWebProviderConfig): String =
    if (config.imageProvider) {
        config.modelId
    } else if (config.modelId.endsWith("-web")) {
        config.modelId.removeSuffix("-web") + "-image-web"
    } else {
        "${config.modelId}-image"
    }

internal fun parseArenaTextStream(raw: String): String = buildString {
    raw.lineSequence().forEach { line ->
        val payload = when {
            line.startsWith("a0:") -> line.removePrefix("a0:")
            line.startsWith("0:") -> line.removePrefix("0:")
            else -> return@forEach
        }.trim()
        val text = runCatching { Json.parseToJsonElement(payload).jsonPrimitive.content }
            .getOrDefault("")
        if (text.isNotEmpty()) append(text)
    }
}

internal fun isArenaGuestWebConfig(config: CustomWebProviderConfig): Boolean {
    if (config.id == "builtin:ARENA_GUEST") return true
    val host = runCatching { URI(config.loginUrl).host.orEmpty().lowercase() }.getOrDefault("")
    return host == "arena.ai" || host.endsWith(".arena.ai")
}

internal fun normalizeArenaGuestUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    val host = uri.host.orEmpty().lowercase()
    if (host != "arena.ai" && !host.endsWith(".arena.ai")) return url
    return if (uri.path.isNullOrBlank() || uri.path == "/") {
        "https://arena.ai/text/direct"
    } else {
        url
    }
}

internal fun isArenaGeneratedImageUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host.orEmpty().lowercase()
    val name = uri.path.orEmpty().substringAfterLast('/').lowercase()
    return host.endsWith(".r2.cloudflarestorage.com") &&
        (name.endsWith(".png") || name.endsWith(".jpg") ||
            name.endsWith(".jpeg") || name.endsWith(".webp"))
}

// Chat and image generation share one WebView. A mode switch from one request
// must not race the other automation path.
private val customWebAutomationLock = Mutex()

/**
 * A custom provider can contain a stale selector, a copied placeholder, or CSS
 * that became invalid after the site changed. Never let querySelector() abort
 * the whole automation; fall back to semantic element discovery instead.
 */
internal fun configuredElementExpression(selector: String, fallback: String): String {
    if (selector.isBlank()) return fallback
    val quoted = JsonPrimitive(selector.trim()).toString()
    return "(()=>{try{return document.querySelector($quoted)}catch(_){return null}})()||($fallback)"
}

class CustomWebProviderStore(context: Context) {
    private val prefs = context.getSharedPreferences("custom_web_providers", Context.MODE_PRIVATE)

    fun load(): List<CustomWebProviderConfig> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val loginUrl = normalizeArenaGuestUrl(obj.optString("loginUrl"))
            val imageProvider = obj.optBoolean("imageProvider", false) || isKnownCustomWebImagePage(loginUrl)
            val storedModelId = obj.optString("modelId")
            val migratedModelId = when {
                loginUrl.contains("image.dddd.zone", ignoreCase = true) &&
                    storedModelId.equals("image-web", ignoreCase = true) -> "image-dddd-image-web"
                imageProvider && storedModelId == "sps-web" -> "sps-image-web"
                else -> storedModelId
            }
            CustomWebProviderConfig(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = obj.optString("name"),
                modelId = migratedModelId,
                loginUrl = loginUrl,
                inputSelector = obj.optString("inputSelector"),
                sendSelector = obj.optString("sendSelector"),
                responseSelector = obj.optString("responseSelector").ifBlank {
                    // longcat 回复在 .v-chat-assistant-message（markdown 正文），
                    // 留空会走整页 innerText 兜底，把导航/输入框按钮误当回复。
                    if (loginUrl.contains("longcat.chat", true)) ".v-chat-assistant-message" else ""
                },
                imageProvider = imageProvider,
            ).takeIf { it.name.isNotBlank() && it.modelId.isNotBlank() && it.loginUrl.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    fun save(config: CustomWebProviderConfig) {
        val updated = load().filterNot {
            it.id == config.id || it.modelId.equals(config.modelId, true)
        } + config
        val array = JSONArray()
        updated.forEach { item -> array.put(JSONObject().apply {
            put("id", item.id); put("name", item.name); put("modelId", item.modelId)
            put("loginUrl", item.loginUrl); put("inputSelector", item.inputSelector)
            put("sendSelector", item.sendSelector); put("responseSelector", item.responseSelector)
            put("imageProvider", item.imageProvider)
        }) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun delete(id: String) = prefs.edit().putString(KEY, JSONArray().apply {
        load().filterNot { it.id == id }.forEach { item -> put(JSONObject().apply {
            put("id", item.id); put("name", item.name); put("modelId", item.modelId)
            put("loginUrl", item.loginUrl); put("inputSelector", item.inputSelector)
            put("sendSelector", item.sendSelector); put("responseSelector", item.responseSelector)
            put("imageProvider", item.imageProvider)
        }) }
    }.toString()).apply()

    companion object { private const val KEY = "providers_v1" }
}

object CustomWebProviderRegistry {
    @Volatile private var configs: List<CustomWebProviderConfig> = emptyList()
    fun set(value: List<CustomWebProviderConfig>) { configs = value.toList() }
    fun find(id: String?): CustomWebProviderConfig? = configs.firstOrNull { it.id == id }
}

fun isBuiltInBrowserRelay(provider: WebProviderType): Boolean = provider in setOf(
    WebProviderType.QWEN_AI_WEB,
    WebProviderType.MINIMAX_WEB,
    WebProviderType.MIMO_WEB,
    WebProviderType.PERPLEXITY_WEB,
    WebProviderType.ZAI_WEB,
    WebProviderType.ARENA_GUEST,
)

fun builtInBrowserRelayConfig(provider: WebProviderType): CustomWebProviderConfig =
    CustomWebProviderConfig(
        id = "builtin:${provider.name}",
        name = provider.displayName,
        modelId = ProviderCatalog.models.firstOrNull {
            it.providerType == provider && ProviderCapability.CHAT in it.capabilities
        }?.publicModelId ?: provider.name.lowercase(),
        loginUrl = provider.loginUrl.orEmpty(),
        // Keep selectors only where the site exposes stable semantic contracts.
        inputSelector = when (provider) {
            WebProviderType.MIMO_WEB -> "textarea[placeholder='有问题，尽管问']"
            WebProviderType.ARENA_GUEST -> "textarea[placeholder='Ask anything…']"
            else -> ""
        },
        sendSelector = when (provider) {
            WebProviderType.MIMO_WEB -> "[data-track-id='claw_send_btn']"
            WebProviderType.ARENA_GUEST -> "button[type='submit']"
            else -> ""
        },
        responseSelector = when (provider) {
            WebProviderType.MIMO_WEB -> ".markdown-prose"
            else -> ""
        },
    )

object CustomWebChatAutomation {
    private val main = Handler(Looper.getMainLooper())
    private val lock = customWebAutomationLock
    @Volatile private var attached: Pair<WebView, String>? = null

    fun attach(view: WebView, configId: String) { attached = view to configId }
    fun detach(view: WebView) { if (attached?.first === view) attached = null }
    fun attachedConfigId(): String? = attached?.second

    /**
     * 常驻拦截"暂无权限访问"类 Vant toast：LongCat 等网页加载时会因非关键
     * 接口（会员/权限检查）返回无权限而弹 toast，但聊天主体正常可用。
     * 页面加载完成后注入 MutationObserver（300ms 防抖），任何时刻新出现的
     * 匹配 toast 都会被自动移除，避免切到供应商页面时闪出弹窗。
     * 幂等：同页重复调用不会重复注入（window.__multiwebPermBlocker 标记）。
     */
    fun armPermissionToastBlocker(view: WebView) {
        if (!view.isAttachedToWindow) return
        main.post {
            view.evaluateJavascript(
                """
                (()=>{try{
                  if(window.__multiwebPermBlocker)return 'dup';
                  let timer=null;
                  const RE=/暂无权限|没有权限|无权限|无权访问|访问受限|权限不足/;
                  const clean=()=>{timer=null;
                    const nodes=[...document.querySelectorAll(
                      '[class*="van-toast"],[class*="van-notify"],[class*="van-dialog"],[class*="toast"],[class*="popup"],[role="dialog"]'
                    )];
                    for(const e of nodes){
                      const t=(e.innerText||e.textContent||'').trim();
                      if(t.length>0&&t.length<80&&RE.test(t)){
                        e.style.display='none';
                        // 同批移除无子节点的遮罩层，避免留黑底
                        const mask=document.querySelector('.van-overlay,[class*="overlay"]');
                        if(mask&&!mask.childElementCount)mask.remove();
                        e.remove();
                      }
                    }
                  };
                  const schedule=()=>{if(!timer)timer=setTimeout(clean,300)};
                  clean();
                  const mo=new MutationObserver(schedule);
                  mo.observe(document.documentElement||document.body,{childList:true,subtree:true});
                  window.__multiwebPermBlocker=mo;
                  return 'armed';
                }catch(e){return String(e)}})()
                """.trimIndent(),
                null,
            )
        }
    }

    suspend fun chat(config: CustomWebProviderConfig, messages: List<ChatMessage>, onUpdate: suspend (String) -> Unit): String = lock.withLock {
        val (view, _) = awaitAttached(config)
        checkArenaGuestBlocker(view, config)
        ensureArenaTextMode(view, config)
        checkArenaGuestBlocker(view, config)
        // 确保聊天输入框就绪（豆包等网页默认停在列表/首页，没有输入框时会
        // 误选搜索框导致发送失败）。等待输入框出现，必要时点"新对话"。
        ensureChatInputReady(view, config)
        // 关闭/隐藏网页推广横幅（豆包的"下载电脑客户端"卡片等会遮挡聊天区）
        dismissPromotionBanners(view, config)
        // 通用图片上传：与 ChatGPT/Gemini 同一套 input[type=file] 注入脚本，
        // 不依赖特定账号或页面结构，任意已登录会话均可用。
        val attachments = messages.flatMap(ChatMessage::attachments)
        val hasImageAttachments = attachments.isNotEmpty()
        if (attachments.isNotEmpty()) {
            uploadAttachments(view, attachments, config)
        }
        val textMessages = messages.filter { it.textContent().isNotBlank() }
        val prompt = if (textMessages.size == 1 && textMessages[0].role == "user") {
            textMessages[0].textContent()
        } else {
            textMessages.joinToString("\n\n") { "[${it.role}]: ${it.textContent()}" }
        }
        val response = JSONObject.quote(config.responseSelector)
        val text = JSONObject.quote(prompt)
        val arenaGuest = isArenaGuestWebConfig(config)
        val autoInput = """(()=>{const visible=e=>{if(!e||e.disabled||e.readOnly||e.offsetParent===null)return false;const s=getComputedStyle(e),r=e.getBoundingClientRect();return s.visibility!=='hidden'&&s.display!=='none'&&Number(s.opacity)>0.05&&r.width>80&&r.height>18};const score=e=>{const p=((e.getAttribute('placeholder')||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();let n=0;if(e.tagName==='TEXTAREA')n+=120;if(e.isContentEditable)n+=100;if(e.getAttribute('role')==='textbox')n+=80;if(/message|prompt|chat|ask|输入|消息|提问/.test(p))n+=90;if(e.getAttribute('role')==='combobox')n-=200;if(e.type==='search')n-=100;const r=e.getBoundingClientRect();n+=Math.min(40,r.top/innerHeight*40);n+=Math.min(20,r.width/innerWidth*20);return n};return [...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"],input[type="text"],input:not([type])')].filter(visible).sort((a,b)=>score(b)-score(a))[0]||null})()"""
        val inputElement = if (arenaGuest) {
            "[...document.querySelectorAll('textarea')].find(e=>/ask anything/i.test(e.getAttribute('placeholder')||''))"
        } else if (config.loginUrl.contains("longcat.chat", ignoreCase = true)) {
            // longcat 用 Tiptap/ProseMirror 富文本编辑器(contenteditable)
            "document.querySelector('.tiptap.ProseMirror,[contenteditable=\"true\"][role=\"textbox\"]')"
        } else {
            configuredElementExpression(config.inputSelector, autoInput)
        }
        val autoSend = """
            (() => {
              const input=$autoInput;
              const visible=e=>{
                if(!e||e.offsetParent===null)return false;
                const s=getComputedStyle(e),r=e.getBoundingClientRect();
                return s.visibility!=='hidden'&&s.display!=='none'&&
                  Number(s.opacity)>0.05&&r.width>20&&r.height>20;
              };
              const metadata=e=>(
                (e.id||'')+' '+(e.getAttribute('aria-label')||'')+' '+
                (e.getAttribute('title')||'')+' '+(e.getAttribute('data-testid')||'')+' '+
                (e.getAttribute('data-action')||'')+' '+(e.className||'')+' '+
                (e.innerText||'')+' '+(e.innerHTML||'')
              ).toLowerCase();
              const score=e=>{
                if(!visible(e)||e.getAttribute('role')==='switch')return -999;
                const h=metadata(e),r=e.getBoundingClientRect();
                let n=0;
                if(/lucide-send|icon-send|send|submit|arrow.?up|paper.?plane|发送|提问|提交/.test(h))n+=220;
                if(/attach|upload|image|photo|mic|voice|tool|search|model|think|文件|图片|语音|工具|搜索|模型|思考/.test(h))n-=180;
                // 容器类(wrap/container)不是真正的按钮(如 longcat .send-wrap 内嵌 .send-btn)，点击无效，降权让子按钮胜出
                if(/wrap|container/.test((e.className||'').toString()))n-=200;
                if(e.type==='submit')n+=180;
                if(e.tagName==='BUTTON'||e.getAttribute('role')==='button')n+=25;
                if(input){
                  const ir=input.getBoundingClientRect();
                  const dx=Math.abs(r.left-ir.right);
                  const dy=Math.abs((r.top+r.bottom-ir.top-ir.bottom)/2);
                  n+=Math.max(0,140-(dx+dy)/2);
                  if(e.closest('form')&&e.closest('form')===input.closest('form'))n+=120;
                  const parent=input.parentElement;
                  if(parent&&parent.contains(e))n+=70;
                }
                return n;
              };
              const candidates=[...document.querySelectorAll(
                'button,[role="button"],input[type="submit"],a,'+
                '[id*="send" i],[class*="send" i],[data-action*="send" i],'+
                '[aria-label*="发送"],[title*="发送"]'
              )].filter(visible);
              return candidates.map(e=>({e,n:score(e)}))
                .sort((a,b)=>b.n-a.n).find(x=>x.n>140)?.e||null;
            })()
        """.trimIndent()
        val responseValues = if (config.responseSelector.isNotBlank()) {
            "[...document.querySelectorAll($response)].map(e=>(e.innerText||e.textContent||'').trim())"
        } else {
            """
                (() => {
                  const submitted=$text;
                  const visible=e=>{
                    if(!e||e.offsetParent===null)return false;
                    const s=getComputedStyle(e),r=e.getBoundingClientRect();
                    return s.display!=='none'&&s.visibility!=='hidden'&&
                      Number(s.opacity)>0.05&&r.width>40&&r.height>12;
                  };
                  const ownMeta=e=>(
                    (e.id||'')+' '+(e.className||'')+' '+
                    (e.getAttribute?.('data-role')||'')+' '+
                    (e.getAttribute?.('data-testid')||'')+' '+
                    (e.getAttribute?.('data-message-author-role')||'')+' '+
                    (e.getAttribute?.('aria-label')||'')
                  ).toLowerCase();
                  const ai=/assistant|answer|response|bot|bubble--ai|conv--ai|item--ai|speech|(?:^|[-_\s])ai(?:[-_\s]|$)/;
                  const human=/human|bubble--human|conv--human|item--human|role.?user|message.?user|(?:^|[-_\s])user(?:[-_\s]|$)/;
                  const nearestAi=e=>{
                    let node=e;
                    for(let depth=0;node&&depth<5;depth++,node=node.parentElement){
                      const h=ownMeta(node);
                      if(human.test(h))return null;
                      if(ai.test(h))return {node,depth};
                    }
                    return null;
                  };
                  const score=e=>{
                    if(!visible(e)||e.matches('button,input,textarea,nav,aside,header,footer'))return -999;
                    if(e.closest('nav,aside,header,footer,[role="menu"],[role="dialog"]'))return -999;
                    const h=ownMeta(e);
                    if(human.test(h))return -999;
                    const inherited=nearestAi(e);
                    let n=0;
                    if(ai.test(h))n+=240;
                    else if(inherited)n+=Math.max(70,170-inherited.depth*25);
                    if(/data-message-author-role.?assistant|data-role.?assistant/.test(h))n+=180;
                    if(/markdown|speech-text|message-content|answer-content|response-content/.test(h))n+=90;
                    if(/composer|prompt|editor|toolbar|sidebar/.test(h))n-=260;
                    if(/list|wrapper|container|layout|dialogue__content/.test(h))n-=110;
                    if(e.matches('article,[role="article"]'))n+=100;
                    if(e.querySelector('p,pre,code,ol,ul,table,blockquote'))n+=45;
                    const t=(e.innerText||e.textContent||'').trim();
                    if(!t||t.length>100000)return -999;
                    if(!ai.test(h)&&t.includes(submitted))n-=300;
                    const nestedTurns=e.querySelectorAll(
                      '[data-message-author-role],[data-role="assistant"],'+
                      '[class*="item--ai" i],[class*="bubble--ai" i],'+
                      '[class*="item--human" i],[class*="bubble--human" i]'
                    ).length;
                    if(nestedTurns>1)n-=Math.min(500,nestedTurns*120);
                    if(t.length>1)n+=Math.min(50,t.length/20);
                    return n;
                  };
                  const selector=[
                    '[data-message-author-role]','[data-role]','[data-testid]',
                    'article','[role="article"]',
                    '[class*="assistant" i]','[class*="answer" i]',
                    '[class*="response" i]','[class*="message" i]',
                    '[class*="bubble" i]','[class*="speech" i]',
                    '[class*="markdown" i]','[class*="content" i]'
                  ].join(',');
                  const all=[...document.querySelectorAll(selector)].slice(-800);
                  const ranked=all.map((e,index)=>({e,index,n:score(e)}))
                    .filter(x=>x.n>=120)
                    .sort((a,b)=>a.index-b.index||b.n-a.n);
                  const out=[],seen=new Set();
                  for(const {e} of ranked){
                    const t=(e.innerText||e.textContent||'').trim();
                    if(!t||seen.has(t))continue;
                    seen.add(t);
                    out.push(t);
                  }
                  return out;
                })()
            """.trimIndent()
        }
        if (arenaGuest) {
            val capture = evaluate(view, ARENA_STREAM_CAPTURE_SCRIPT)
            if (capture != "ok") throw WebAutomationException(
                "arena_capture_failed",
                "Arena.ai 流式响应监听初始化失败，请刷新网页后重试"
            )
        }
        val baseline = evaluate(view, "JSON.stringify($responseValues)")
        var clicked = false
        if (arenaGuest) {
            val sendDeadline = System.currentTimeMillis() + 15_000
            while (!clicked && System.currentTimeMillis() < sendDeadline) {
                val state = evaluate(view, """
                    (()=>{try{
                      const e=$inputElement;
                      if(!e)return 'input_not_found';
                      if((e.value||'')!==$text){
                        e.focus();
                        const previous=e.value||'';
                        const setter=Object.getOwnPropertyDescriptor(
                          HTMLTextAreaElement.prototype,'value'
                        )?.set;
                        if(setter)setter.call(e,$text);else e.value=$text;
                        if(e._valueTracker)e._valueTracker.setValue(previous);
                        e.dispatchEvent(new InputEvent('input',{
                          bubbles:true,inputType:'insertText',data:$text
                        }));
                        e.dispatchEvent(new Event('change',{bubbles:true}));
                        return 'waiting';
                      }
                      const b=document.querySelector('button[aria-label="Send message"]');
                      if(!b||b.disabled||b.getAttribute('aria-disabled')==='true'){
                        return 'waiting';
                      }
                      b.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));
                      b.click();
                      b.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));
                      return 'sent';
                    }catch(error){return String(error)}})()
                """.trimIndent())
                clicked = state == "sent"
                if (!clicked) delay(200)
            }
        } else {
            val fillResult = evaluate(view, """
                (()=>{try{const e=$inputElement;if(!e)return 'input_not_found';e.focus();
                if(e.tagName==='TEXTAREA'||e.tagName==='INPUT'){const s=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value')?.set;if(s)s.call(e,$text);else e.value=$text;}
                else if(e.classList&&e.classList.contains('ProseMirror')){document.execCommand('insertText',false,$text);}
                else{e.textContent=$text;const range=document.createRange();range.selectNodeContents(e);range.collapse(false);const selection=getSelection();selection.removeAllRanges();selection.addRange(range)}
                e.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,inputType:'insertText',data:$text}));e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:$text}));e.dispatchEvent(new Event('change',{bubbles:true}));e.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'Unidentified'}));const actual=(e.value??e.innerText??e.textContent??'').trim();return actual?'ok':'input_state_not_updated';}catch(x){return String(x)}})()
            """.trimIndent())
            if (fillResult != "ok") {
                throw WebAutomationException(
                    "prompt_fill_failed",
                    "${config.name} 输入失败：$fillResult"
                )
            }
            val sendButton = configuredElementExpression(config.sendSelector, autoSend)
            val sendDeadline = System.currentTimeMillis() + 5_000
            // 豆包网页版无 data-testid 发送按钮，发送靠输入框内 Enter 键。
            // longcat 用 ProseMirror(contenteditable)，Enter 是换行不发送；有明确 .send-btn，走下方点击逻辑。
            val useEnterToSend = config.loginUrl.contains("doubao.com", ignoreCase = true)
            if (useEnterToSend) {
                // 直接派发 Enter 键发送（豆包 semi-input textarea / longcat ProseMirror 编辑器）
                clicked = evaluate(view, """(()=>{try{
                  const e=$inputElement;if(!e)return false;e.focus();
                  e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
                  e.dispatchEvent(new KeyboardEvent('keypress',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
                  e.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
                  return true;
                }catch(_){return false}})()""".trimIndent()) == "true"
            }
            while (!clicked && System.currentTimeMillis() < sendDeadline) {
                clicked = evaluate(view, """(()=>{const b=$sendButton;if(!b||b.disabled||b.getAttribute('aria-disabled')==='true')return false;const e=$inputElement;const before=(e?.value??e?.innerText??e?.textContent??'').trim();b.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));b.click();b.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));if(e?.closest('form')&&before&&(e.value??e.innerText??e.textContent??'').trim()===before){try{e.closest('form').requestSubmit(b.type==='submit'?b:undefined)}catch(_){}}return true})()""") == "true"
                if (!clicked) delay(150)
            }
            if (!clicked) {
                clicked = evaluate(view, """
                    (()=>{try{
                      const e=$inputElement;
                      if(!e)return false;
                      const form=e.closest('form');
                      if(form?.requestSubmit){form.requestSubmit();return true}
                      e.focus();
                      for(const type of ['keydown','keypress','keyup']){
                        e.dispatchEvent(new KeyboardEvent(type,{
                          key:'Enter',code:'Enter',keyCode:13,which:13,
                          bubbles:true,cancelable:true
                        }));
                      }
                      return true;
                    }catch(_){return false}})()
                """.trimIndent()) == "true"
            }
        }
        if (!clicked) throw WebAutomationException(
            "send_button_not_ready",
            "${config.name} 未识别到发送控件，请在高级设置填写发送按钮 CSS"
        )
        // Arena displays its one-time legal consent only after the first submit.
        // Surface it immediately instead of holding the local API request until timeout.
        if (arenaGuest) {
            delay(300)
            checkArenaGuestBlocker(view, config)
        }
        val baselineSet = runCatching { JSONArray(baseline) }.getOrDefault(JSONArray()).let { a ->
            (0 until a.length()).map { a.optString(it) }.toSet()
        }
        // 记录发送前整个页面的 innerText（供 responseSelector 匹配不到时兜底对比）
        val baselineFullText = runCatching {
            evaluate(view, "document.body.innerText||''").trim()
        }.getOrDefault("")
        val deadline = System.currentTimeMillis() + 180_000
        // A disabled send button usually only means the editor is empty after submit.
        // Treating that as generation-in-progress kept the request lock for the full
        // timeout on sites such as Heck, so the next relay message never reached WebView.
        val busyState = """
            (() => {
              if(document.querySelector('[aria-busy="true"],[data-loading="true"],[role="progressbar"]'))return true;
              const candidates=[...document.querySelectorAll(
                'button,[role="button"],[id*="stop" i],[class*="stop" i],'+
                '[id*="cancel" i],[class*="cancel" i],[class*="loading" i]'
              )];
              return candidates.some(e=>{
                if(e.offsetParent===null)return false;
                const h=((e.id||'')+' '+(e.getAttribute('aria-label')||'')+' '+
                  (e.getAttribute('title')||'')+' '+(e.className||'')+' '+
                  (e.innerText||'')+' '+(e.innerHTML||'')).toLowerCase();
                return /stop|cancel|abort|generating|searching|thinking|loading|停止|取消|生成中|搜索中|思考中|加载中|lucide-square|square-stop/.test(h);
              });
            })()
        """.trimIndent()
        var last = ""; var stable = 0; var sawBusy = false
        var lastArenaUpdateAt = 0L
        var lastDismissAt = 0L
        while (System.currentTimeMillis() < deadline) {
            delay(200)
            // 周期性清理网页弹窗/推广横幅（豆包"创建项目"引导等会遮挡聊天区）
            if (System.currentTimeMillis() - lastDismissAt > 1_500) {
                lastDismissAt = System.currentTimeMillis()
                dismissPromotionBanners(view, config)
            }
            val arenaRaw = if (arenaGuest) {
                evaluate(view, "window.__multiwebArenaStream||''")
            } else ""
            val current = if (arenaGuest) {
                parseArenaTextStream(arenaRaw)
            } else {
                val values = runCatching {
                    JSONArray(evaluate(view, "JSON.stringify($responseValues)"))
                }.getOrDefault(JSONArray())
                val matched = (0 until values.length()).map { values.optString(it).trim() }
                    .lastOrNull {
                        it.isNotBlank() && it !in baselineSet && it != prompt.trim()
                    }.orEmpty()
                if (matched.isNotBlank()) {
                    matched
                } else if (config.responseSelector.isNotBlank()) {
                    // 已配置明确回复选择器但暂时匹配不到（AI 回复仍在生成），返回空等待，
                    // 避免 innerText 兜底把导航/输入框/页脚等界面文本误当回复。
                    ""
                } else {
                    // 兜底：responseSelector 未配置时，对比整个页面 innerText 与 baseline。
                    runCatching {
                        val full = evaluate(view, "document.body.innerText||''").trim()
                        if (full.isBlank()) return@runCatching ""
                        if (baselineFullText.isNotEmpty() && full.length > baselineFullText.length
                            && full.contains(baselineFullText)) {
                            val added = full.substring(baselineFullText.length).trim()
                            if (added.length > 5) added else ""
                        } else {
                            val tail = full.takeLast(500)
                            if (tail.length > 10 && tail != prompt.trim()) tail else ""
                        }
                    }.getOrDefault("")
                }
            }
            if (arenaRaw.contains("prompt failed", ignoreCase = true)) {
                delay(300)
                checkArenaGuestBlocker(view, config)
                throw WebAutomationException(
                    "arena_prompt_failed",
                    "Arena.ai 游客请求被拒绝，可能已达到频率限制，请稍后再试"
                )
            }
            val busy = evaluate(view, busyState) == "true"
            sawBusy = sawBusy || busy
            stable = if (current.isNotBlank() && current == last) stable + 1 else 0
            if (current.isNotBlank() && current != last) {
                onUpdate(current)
                last = current
                lastArenaUpdateAt = System.currentTimeMillis()
            }
            if (arenaGuest && last.isNotBlank()) {
                val captureDone = evaluate(view, "window.__multiwebArenaDone===true") == "true"
                val stillOnArena = evaluate(
                    view,
                    "location.hostname==='arena.ai'||location.hostname.endsWith('.arena.ai')",
                ) == "true"
                // Guest Arena may show the complete answer and then redirect to
                // Google sign-in before its intercepted response body closes.
                // Preserve that answer and let the local SSE emit stop/[DONE].
                val answerStable = System.currentTimeMillis() - lastArenaUpdateAt >= 8_000
                if (captureDone || !stillOnArena || answerStable) return@withLock last
            }
            // 豆包等流式渲染页面：文本持续增长时无法连续 N 次相同，用「内容有更新且
            // 已停止增长一段时间」判定完成，而不是死等 stable 15 次。
            val isStreamingPage = config.loginUrl.contains("doubao.com", ignoreCase = true)
            val stableTarget = if (sawBusy && !busy) 3 else 15
            val quietMs = System.currentTimeMillis() - lastArenaUpdateAt
            if (!busy && stable >= stableTarget) return@withLock last
            // 流式页兜底：回复文本非空，且已静默一段时间 → 视为完成。
            // 发图（视觉推理）输出可能分段停顿，静默阈值放宽到 6 秒避免截断；
            // 纯文字 2.5 秒即可。
            val quietThreshold = if (hasImageAttachments) 6_000L else 2_500L
            if (isStreamingPage && last.isNotBlank() && quietMs >= quietThreshold) return@withLock last
        }
        if (arenaGuest && last.isNotBlank()) return@withLock last
        throw WebAutomationException("chat_timeout", "${config.name} 网页回复等待超时")
    }

    private suspend fun awaitAttached(config: CustomWebProviderConfig): Pair<WebView, String> {
        attached?.takeIf { it.second == config.id }?.let { return it }
        BrowserRelayActivation.activate(config.modelId)
        LogStore.i("BrowserRelay", "自动打开 ${config.name}，等待聊天 WebView")
        val deadline = System.currentTimeMillis() + BROWSER_AUTO_ATTACH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(200)
            attached?.takeIf { it.second == config.id }?.let { return it }
        }
        throw WebAutomationException(
            "webview_not_ready",
            "已尝试自动打开 ${config.name}，但网页未在 ${BROWSER_AUTO_ATTACH_TIMEOUT_MS / 1000} 秒内就绪；请打开一次 MultiWeb 浏览器页"
        )
    }

    private suspend fun ensureArenaTextMode(view: WebView, config: CustomWebProviderConfig) {
        if (!isArenaGuestWebConfig(config)) return
        val ready = evaluate(view, """
            (() => {
              if(location.hostname!=='arena.ai'&&!location.hostname.endsWith('.arena.ai'))return false;
              return [...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]')]
                .some(e=>{
                  if(e.offsetParent===null||e.getAttribute('aria-hidden')==='true')return false;
                  const style=getComputedStyle(e);
                  if(style.display==='none'||style.visibility==='hidden')return false;
                  const hint=((e.getAttribute('placeholder')||'')+' '+
                    (e.getAttribute('aria-label')||'')).toLowerCase();
                  return !/describe the image|image prompt/.test(hint);
                });
            })()
        """.trimIndent()) == "true"
        if (ready) return

        loadUrl(view, ARENA_TEXT_URL)
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            checkArenaGuestBlocker(view, config)
            val state = evaluate(view, """
                (() => {
                  if(location.hostname!=='arena.ai'&&!location.hostname.endsWith('.arena.ai'))return 'wrong_host';
                  const input=[...document.querySelectorAll(
                    'textarea,[contenteditable="true"],[role="textbox"]'
                  )].find(e=>{
                    if(e.offsetParent===null||e.getAttribute('aria-hidden')==='true')return false;
                    const style=getComputedStyle(e);
                    if(style.display==='none'||style.visibility==='hidden')return false;
                    const hint=((e.getAttribute('placeholder')||'')+' '+
                      (e.getAttribute('aria-label')||'')).toLowerCase();
                    return !/describe the image|image prompt/.test(hint);
                  });
                  return input?'ready':'loading';
                })()
            """.trimIndent())
            if (state == "ready") return
            if (state == "wrong_host") {
                throw WebAutomationException(
                    "arena_page_unavailable",
                    "Arena.ai 文本页面已跳转离开，请在浏览器返回 Arena 后重试"
                )
            }
        }
        throw WebAutomationException(
            "arena_text_mode_timeout",
            "Arena.ai 文本模式加载超时，请在浏览器打开 Text 页面后重试"
        )
    }

    private suspend fun checkArenaGuestBlocker(view: WebView, config: CustomWebProviderConfig) {
        if (!isArenaGuestWebConfig(config)) return
        val blocker = evaluate(view, """
            (() => {
              const visible=e=>{
                if(!e||e.offsetParent===null)return false;
                const s=getComputedStyle(e);
                return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)>0.05;
              };
              const dialog=[...document.querySelectorAll('[role="dialog"]')].find(visible);
              const dialogText=(dialog?.innerText||dialog?.textContent||'').toLowerCase();
              if(dialog&&/terms of use|privacy policy/.test(dialogText)&&
                [...dialog.querySelectorAll('button')].some(b=>/\bagree\b/i.test(b.innerText||''))){
                return 'terms';
              }
              const pageText=(document.body?.innerText||'').toLowerCase();
              const challenge=[...document.querySelectorAll(
                'iframe[title*="recaptcha challenge" i],iframe[src*="/recaptcha/api2/bframe" i]'
              )].some(visible)||/security verification|verify you are human|验证您是真人/.test(pageText);
              return challenge?'captcha':'';
            })()
        """.trimIndent())
        when (blocker) {
            "terms" -> throw WebAutomationException(
                "terms_required",
                "Arena.ai 可免登录使用，但首次需在浏览器点 Agree 同意条款"
            )
            "captcha" -> throw WebAutomationException(
                "captcha_required",
                "Arena.ai 触发 reCAPTCHA，请在浏览器人工完成验证后重试"
            )
        }
    }

    /**
     * 确保聊天输入框就绪：有些网页（豆包等）打开后停在列表/首页，没有聊天
     * 输入框；此时若直接填 prompt 会误选搜索框。等待可见输入框出现，必要时
     * 点击"新对话/新建"按钮或最近会话项。超时不抛错（后续流程会报 input_not_found）。
     */
    private suspend fun ensureChatInputReady(view: WebView, config: CustomWebProviderConfig) {
        val hasInput = runCatching {
            evaluate(view, """
                (()=>{
                  return [...document.querySelectorAll(
                    'textarea,[contenteditable="true"],[role="textbox"]'
                  )].some(e=>{
                    if(e.offsetParent===null||e.getAttribute('aria-hidden')==='true')return false;
                    const s=getComputedStyle(e);
                    return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)>0.05;
                  });
                })()
            """.trimIndent()) == "true"
        }.getOrDefault(false)
        if (hasInput) return
        // 尝试点"新对话/新建"按钮
        runCatching {
            evaluate(view, """
                (()=>{
                  const b=[...document.querySelectorAll('button,[role="button"]')].find(e=>{
                    if(e.offsetParent===null)return false;
                    const t=((e.getAttribute('aria-label')||'')+' '+(e.title||'')+' '+(e.innerText||'')).trim();
                    return /new chat|新对话|新建对话|新建/.test(t)||
                      (e.className&&/new-?chat/i.test(String(e.className)));
                  });
                  if(b){b.click();return true;}
                  return false;
                })()
            """.trimIndent())
        }
        // 等待输入框出现
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            val ok = runCatching {
                evaluate(view, """
                    (()=>{
                      return [...document.querySelectorAll(
                        'textarea,[contenteditable="true"],[role="textbox"]'
                      )].some(e=>{
                        if(e.offsetParent===null)return false;
                        const s=getComputedStyle(e);
                        return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)>0.05;
                      });
                    })()
                """.trimIndent()) == "true"
            }.getOrDefault(false)
            if (ok) return
        }
        LogStore.w("CustomWeb", "${config.name} 聊天输入框未就绪，可能误选搜索框")
    }

    /**
     * 关闭/隐藏网页推广横幅与引导弹窗：豆包网页的"下载电脑客户端 / 立即下载"
     * 卡片、"创建项目"引导弹窗、更新弹窗等会遮挡聊天输入区。通用匹配文案，
     * 隐藏横幅 + 点击取消/关闭按钮。
     */
    private suspend fun dismissPromotionBanners(view: WebView, config: CustomWebProviderConfig) {
        runCatching {
            evaluate(view, """
                (()=>{try{
                  const kw=/下载电脑客户端|立即下载|download.{0,20}client|get.{0,10}(?:app|client)|下载app|安装app/i;
                  const isVisible=e=>{
                    if(!e||e.offsetParent===null)return false;
                    const s=getComputedStyle(e);
                    return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)>0.05;
                  };
                  // 0) 隐藏 longcat/通用站点的"暂无权限访问"等 Vant toast 提示
                  const permToasts=[...document.querySelectorAll(
                    '[class*="van-toast"],[class*="toast"],[class*="van-notify"],[class*="van-dialog"]'
                  )].filter(e=>{
                    if(!e)return false;
                    const t=(e.innerText||e.textContent||'').trim();
                    return /暂无权限|没有权限|无权限|无权访问|权限不足|访问受限/.test(t) && t.length<60;
                  });
                  permToasts.forEach(e=>{e.style.display='none';e.remove()});
                  // 0a) 关闭可见引导弹窗：点"取消/暂不/稍后/知道了"类按钮
                  const dialogs=[...document.querySelectorAll('[role="dialog"],[class*="modal" i],[class*="dialog" i],[class*="popup" i],[class*="drawer" i]')].filter(isVisible);
                  for(const dlg of dialogs){
                    const dlgText=(dlg.innerText||dlg.textContent||'').toLowerCase();
                    if(/项目名称|创建项目|create project/.test(dlgText)){
                      const cancel=[...dlg.querySelectorAll('button')].find(b=>/取消|暂不|稍后|跳过|关闭|cancel|skip|close/i.test((b.innerText||b.getAttribute('aria-label')||'').trim()));
                      if(cancel){cancel.click();continue;}
                    }
                  }
                  // 0b) 兜底：全页面找"创建项目"弹窗里的取消按钮（豆包弹窗可能无 role=dialog）
                  const pageText=(document.body.innerText||'').toLowerCase();
                  if(/创建项目|项目名称/.test(pageText)){
                    const anyCancel=[...document.querySelectorAll('button')].find(b=>{
                      if(!isVisible(b))return false;
                      return /^取消$|^暂不$|^稍后再说$|^跳过$|^关闭$/.test((b.innerText||'').trim());
                    });
                    if(anyCancel)anyCancel.click();
                  }
                  // 1) 找含推广文案的可关闭卡片/弹窗
                  const nodes=[...document.querySelectorAll(
                    'div,section,aside,[role="dialog"],[class*="download" i],[class*="banner" i],[class*="promo" i],[class*="toast" i],[class*="popup" i]'
                  )].filter(e=>{
                    if(!e||!isVisible(e))return false;
                    const t=(e.innerText||e.textContent||'').trim();
                    if(t.length>400||!kw.test(t))return false;
                    // 只处理靠近顶部的横幅或浮层
                    const r=e.getBoundingClientRect();
                    return r.width>200&&r.height<400;
                  });
                  nodes.forEach(e=>{e.style.display='none';e.remove()});
                  // 2) 找关闭按钮(X/关闭/稍后再说)并点击
                  const close=[...document.querySelectorAll('button,[role="button"]')].find(b=>{
                    if(!isVisible(b))return false;
                    const t=((b.getAttribute('aria-label')||'')+' '+(b.title||'')+' '+(b.innerText||'')).trim();
                    return /^×$|^✕$|关闭|关掉|稍后|暂不|不再提示|知道了/.test(t);
                  });
                  if(close)close.click();
                  // 3) 兜底: 隐藏含推广文案的最近祖先
                  const any=[...document.querySelectorAll('div,section')].find(e=>{
                    if(!e||!isVisible(e))return false;
                    const t=(e.innerText||'').trim();
                    return t.length<200&&kw.test(t);
                  });
                  if(any){any.style.display='none';}
                  return 'ok';
                }catch(e){return String(e)}})()
            """.trimIndent())
        }
        // 等弹窗关闭动画
        delay(200)
    }

    /**
     * 通用图片上传：两阶段注入，兼容两类站点——
     *  1) 静态 input[type=file]（ChatGPT/Gemini 等）：直接 DataTransfer 注入；
     *  2) 动态创建 input（豆包网页版等：按钮点击 → createElement('input') → .click()）：
     *     arm 脚本 hook click 拦截，把文件塞进 DataTransfer 并触发 change，不弹系统选择器。
     * 不依赖特定账号或页面结构，任意已登录的 Web 供应商会话均可用。
     */
    private suspend fun uploadAttachments(
        view: WebView,
        attachments: List<ChatAttachment>,
        config: CustomWebProviderConfig,
    ) {
        // 阶段1：arm（hook + 预置文件；若已有可见静态 input 则直接注入）
        var state = runCatching { result(evaluate(view, webAttachmentArmScript(attachments))) }
            .getOrDefault(JSONObject())
        if (!state.optBoolean("ok")) {
            // arm 失败（页面异常）→ 回退旧式直接注入
            state = runCatching { result(evaluate(view, webAttachmentUploadScript(attachments))) }
                .getOrDefault(JSONObject())
        }
        val mode = state.optString("mode", "")
        if (state.optBoolean("ok") && mode == "armed") {
            // 阶段2：点上传按钮，触发动态 input.click() → 被 hook 拦截注入
            runCatching { evaluate(view, WEB_ATTACHMENT_BUTTON_SCRIPT) }
            // 轮询注入结果（按钮 handler 可能异步）
            val deadline = System.currentTimeMillis() + 4_000
            var injected = false
            while (System.currentTimeMillis() < deadline) {
                delay(250)
                val r = runCatching { result(evaluate(view, WEB_ATTACHMENT_RESULT_SCRIPT)) }
                    .getOrDefault(JSONObject())
                if (r.optString("result") == "injected") { injected = true; break }
                if (r.optString("result").startsWith("error")) break
            }
            if (injected) {
                LogStore.i("CustomWeb", "${config.name} 已附加 ${attachments.size} 个附件（动态注入）")
            } else {
                LogStore.w("CustomWeb", "${config.name} 未检测到文件注入，图片可能未附加")
            }
        } else if (state.optBoolean("ok") && mode == "direct") {
            LogStore.i("CustomWeb", "${config.name} 已附加 ${attachments.size} 个附件（直接注入）")
        } else {
            // 静态 input 路径失败 → 尝试点按钮 + 旧式注入
            runCatching { evaluate(view, WEB_ATTACHMENT_BUTTON_SCRIPT) }
            delay(700)
            val retry = runCatching { result(evaluate(view, webAttachmentUploadScript(attachments))) }
                .getOrDefault(JSONObject())
            if (retry.optBoolean("ok")) {
                LogStore.i("CustomWeb", "${config.name} 已附加 ${attachments.size} 个附件")
            } else {
                LogStore.w("CustomWeb", "${config.name} 未找到文件上传控件，图片可能未附加")
            }
        }
        delay(400)
    }

    private fun result(value: String): JSONObject = runCatching { JSONObject(value) }.getOrElse { JSONObject() }

    private suspend fun loadUrl(view: WebView, url: String) {
        suspendCancellableCoroutine { cont ->
            main.post {
                if (!cont.isActive) return@post
                view.loadUrl(url)
                cont.resume(Unit)
            }
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String =
        withTimeoutOrNull(WEBVIEW_EVALUATE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                main.post {
                    if (!cont.isActive) return@post
                    if (!view.isAttachedToWindow) {
                        cont.resume("")
                        return@post
                    }
                    view.evaluateJavascript(script) { raw ->
                        if (cont.isActive) cont.resume(decode(raw))
                    }
                }
            }
        } ?: ""  // 页面主线程繁忙时 evaluate 可能延迟，返回空串让调用方容错重试
    private fun decode(raw: String?): String = if (raw == null || raw == "null") "" else
        runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault(raw)

    private val ARENA_STREAM_CAPTURE_SCRIPT = """
        (() => {
          try {
            window.__multiwebArenaStream='';
            window.__multiwebArenaDone=false;
            window.__multiwebArenaError='';
            if(window.__multiwebArenaFetchWrapped)return 'ok';
            const originalFetch=window.fetch.bind(window);
            window.fetch=async (...args)=>{
              const response=await originalFetch(...args);
              try{
                const input=args[0];
                const url=typeof input==='string'?input:(input?.url||'');
                const method=String(args[1]?.method||input?.method||'GET').toUpperCase();
                if(url.includes('/nextjs-api/stream')&&method==='POST'){
                  window.__multiwebArenaStream='';
                  window.__multiwebArenaDone=false;
                  window.__multiwebArenaError='';
                  const clone=response.clone();
                  (async()=>{
                    try{
                      if(!clone.body){
                        window.__multiwebArenaStream=await clone.text();
                        return;
                      }
                      const reader=clone.body.getReader();
                      const decoder=new TextDecoder();
                      while(true){
                        const part=await reader.read();
                        if(part.done)break;
                        window.__multiwebArenaStream+=decoder.decode(part.value,{stream:true});
                      }
                      window.__multiwebArenaStream+=decoder.decode();
                    }catch(error){
                      window.__multiwebArenaError=String(error);
                    }finally{
                      window.__multiwebArenaDone=true;
                    }
                  })();
                }
              }catch(error){
                window.__multiwebArenaError=String(error);
              }
              return response;
            };
            window.__multiwebArenaFetchWrapped=true;
            return 'ok';
          }catch(error){
            return String(error);
          }
        })()
    """.trimIndent()

    private const val ARENA_TEXT_URL = "https://arena.ai/text/direct"
}

object CustomWebImageAutomation {
    private val main = Handler(Looper.getMainLooper())
    private val lock = customWebAutomationLock
    private val imageClient = OkHttpClient()
    @Volatile private var attached: Pair<WebView, String>? = null

    fun attach(view: WebView, configId: String) { attached = view to configId }
    fun detach(view: WebView) { if (attached?.first === view) attached = null }
    fun attachedConfigId(): String? = attached?.second

    suspend fun generateImage(config: CustomWebProviderConfig, prompt: String, ratio: String?, refImages: List<ChatAttachment> = emptyList()): List<String> = lock.withLock {
        val (view, _) = awaitAttached(config)
        if (isArenaGuestWebConfig(config)) {
            return@withLock generateArenaImage(view, config, prompt, ratio)
        }
        if (isKnownCustomWebHybridImagePage(config.loginUrl)) {
            return@withLock generateLongCat(view, config, prompt, refImages)
        }
        if (config.loginUrl.contains("image.dddd.zone", ignoreCase = true)) {
            return@withLock generateDddd(view, config, prompt)
        }
        if (!config.imageProvider) {
            return@withLock generateChatPageImage(view, config, prompt, ratio)
        }
        val size = when (ratio) {
            "16:9" -> "1280x720"
            "9:16" -> "720x1280"
            else -> "1024x1024"
        }
        val baseline = evaluate(view, "document.querySelector('#generatedImage')?.src||''")
        val result = evaluate(view, """
            (()=>{try{
              const input=document.querySelector('#prompt')||document.querySelector('textarea');
              const button=document.querySelector('#generateBtn')||[...document.querySelectorAll('button')].find(b=>/生成图片|generate/i.test(b.innerText||''));
              if(!input)return 'input_not_found'; if(!button)return 'send_not_found';
              const setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(input),'value')?.set;
              if(setter)setter.call(input,${JSONObject.quote(prompt)});else input.value=${JSONObject.quote(prompt)};
              input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:${JSONObject.quote(prompt)}}));
              const size=document.querySelector('#size'); if(size){size.value=${JSONObject.quote(size)};size.dispatchEvent(new Event('change',{bubbles:true}));}
              button.click(); return 'ok';
            }catch(e){return String(e)}})()
        """.trimIndent())
        if (result != "ok") throw WebAutomationException("image_submit_failed", "${config.name} 生图提交失败：$result")
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            val state = evaluate(view, """(()=>{const i=document.querySelector('#generatedImage');const e=document.querySelector('#error');return JSON.stringify({src:i?.src||'',shown:!!i&&i.offsetParent!==null,error:e&&e.offsetParent!==null?(e.innerText||''):''})})()""")
            val obj = runCatching { JSONObject(state) }.getOrDefault(JSONObject())
            val src = obj.optString("src")
            if (obj.optBoolean("shown") && src.isNotBlank() && src != baseline) return@withLock listOf(src)
            obj.optString("error").takeIf { it.isNotBlank() }?.let {
                throw WebAutomationException("upstream_page_error", it)
            }
        }
        throw WebAutomationException("image_timeout", "${config.name} 网页生图等待超时")
    }

    private suspend fun awaitAttached(config: CustomWebProviderConfig): Pair<WebView, String> {
        attached?.takeIf { it.second == config.id }?.let { return it }
        BrowserRelayActivation.activate(config.modelId)
        LogStore.i("BrowserRelay", "自动打开 ${config.name}，等待生图 WebView")
        val deadline = System.currentTimeMillis() + BROWSER_AUTO_ATTACH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(200)
            attached?.takeIf { it.second == config.id }?.let { return it }
        }
        throw WebAutomationException(
            "webview_not_ready",
            "已尝试自动打开 ${config.name}，但网页未在 ${BROWSER_AUTO_ATTACH_TIMEOUT_MS / 1000} 秒内就绪；请打开一次 MultiWeb 浏览器页"
        )
    }

    private suspend fun generateArenaImage(
        view: WebView,
        config: CustomWebProviderConfig,
        prompt: String,
        ratio: String?,
    ): List<String> {
        checkArenaImageBlocker(view)
        val imageModeReady = evaluate(view, ARENA_IMAGE_MODE_READY_SCRIPT) == "true"
        if (!imageModeReady) {
            loadUrl(view, ARENA_IMAGE_URL)
            val loadDeadline = System.currentTimeMillis() + 45_000
            var ready = false
            while (!ready && System.currentTimeMillis() < loadDeadline) {
                delay(300)
                checkArenaImageBlocker(view)
                val state = evaluate(view, """
                    (() => {
                      if(location.hostname!=='arena.ai'&&!location.hostname.endsWith('.arena.ai')){
                        return 'wrong_host';
                      }
                      return ($ARENA_IMAGE_MODE_READY_SCRIPT)?'ready':'loading';
                    })()
                """.trimIndent())
                if (state == "wrong_host") {
                    throw WebAutomationException(
                        "arena_page_unavailable",
                        "Arena.ai 生图页面已跳转离开，请在浏览器返回 Arena 后重试"
                    )
                }
                ready = state == "ready"
            }
            if (!ready) {
                throw WebAutomationException(
                    "arena_image_mode_timeout",
                    "Arena.ai Image 模式加载超时，请在浏览器打开 Image 页面后重试"
                )
            }
        }

        val baselineJson = evaluate(
            view,
            "JSON.stringify([...document.images].map(i=>i.currentSrc||i.getAttribute('src')||i.src||'').filter(Boolean))"
        )
        val baseline = runCatching { JSONArray(baselineJson) }.getOrDefault(JSONArray()).let { array ->
            (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank).toSet()
        }
        val requestedPrompt = buildString {
            ratio?.takeIf(String::isNotBlank)?.let {
                append("画面比例为 ").append(it).append("：\n")
            }
            append(prompt)
        }
        val text = JSONObject.quote(requestedPrompt)
        // The textarea can appear before React finishes hydrating. In that window
        // the first programmatic value is reset. Re-apply and verify the controlled
        // value until its submit button becomes enabled.
        val sendDeadline = System.currentTimeMillis() + 15_000
        var sent = false
        while (!sent && System.currentTimeMillis() < sendDeadline) {
            val submitState = evaluate(view, """
                (() => {
                  try {
                    const input=[...document.querySelectorAll('textarea')]
                      .find(e=>/describe the image/i.test(e.getAttribute('placeholder')||''));
                    if(!input)return 'input_not_found';
                    if((input.value||'')!==$text){
                      input.focus();
                      const previous=input.value||'';
                      const setter=Object.getOwnPropertyDescriptor(
                        HTMLTextAreaElement.prototype,'value'
                      )?.set;
                      if(setter)setter.call(input,$text);else input.value=$text;
                      if(input._valueTracker)input._valueTracker.setValue(previous);
                      input.dispatchEvent(new InputEvent('input',{
                        bubbles:true,inputType:'insertText',data:$text
                      }));
                      input.dispatchEvent(new Event('change',{bubbles:true}));
                      return 'waiting';
                    }
                    const button=document.querySelector('button[aria-label="Send message"]');
                    if(!button||button.disabled||button.getAttribute('aria-disabled')==='true'){
                      return 'waiting';
                    }
                    button.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));
                    button.click();
                    button.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));
                    return 'sent';
                  } catch(error) {
                    return String(error);
                  }
                })()
            """.trimIndent())
            sent = submitState == "sent"
            if (!sent) delay(200)
        }
        if (!sent) {
            throw WebAutomationException(
                "image_submit_failed",
                "${config.name} Image 模式发送按钮尚未就绪"
            )
        }

        delay(400)
        checkArenaImageBlocker(view)
        val deadline = System.currentTimeMillis() + 300_000
        var lastFresh = emptyList<String>()
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            checkArenaImageBlocker(view)
            val state = runCatching {
                JSONObject(evaluate(view, """
                    (() => JSON.stringify({
                      images:[...document.images]
                        .map(i=>i.currentSrc||i.getAttribute('src')||i.src||'')
                        .filter(Boolean),
                      busy:!!document.querySelector('button[aria-label="Stop generation"]'),
                      text:(document.body?.innerText||'').slice(-1500)
                    }))()
                """.trimIndent()))
            }.getOrDefault(JSONObject())
            val images = state.optJSONArray("images") ?: JSONArray()
            val fresh = (0 until images.length()).map { images.optString(it) }
                .filter {
                    it.isNotBlank() && it !in baseline && isArenaGeneratedImageUrl(it)
                }
                .distinct()
                .takeLast(4)
            val busy = state.optBoolean("busy")
            stable = if (fresh.isNotEmpty() && fresh == lastFresh) stable + 1 else 0
            lastFresh = fresh
            if (!busy && stable >= 3) {
                val pageUrl = evaluate(view, "location.href||''").ifBlank { ARENA_IMAGE_URL }
                val cached = fresh.map { url ->
                    runCatching { cacheWebImage(url, pageUrl) }.getOrElse { error ->
                        LogStore.w("CustomWebImage", "Arena 图片缓存失败：${error.message}")
                        url
                    }
                }
                LogStore.i(
                    "CustomWebImage",
                    "${config.name} Arena 生图完成，提取到 ${cached.size} 张图片"
                )
                return cached
            }
            val pageText = state.optString("text")
            if (ARENA_REJECTION_PATTERN.containsMatchIn(pageText)) {
                throw WebAutomationException(
                    "arena_image_rejected",
                    "Arena.ai 游客生图请求被拒绝，可能已达到频率限制，请稍后再试"
                )
            }
        }
        if (lastFresh.isNotEmpty()) {
            val cached = lastFresh.map { url ->
                runCatching { cacheWebImage(url, ARENA_IMAGE_URL) }.getOrElse { url }
            }
            LogStore.i(
                "CustomWebImage",
                "${config.name} Arena 生图超时前提取到 ${cached.size} 张图片"
            )
            return cached
        }
        throw WebAutomationException(
            "image_timeout",
            "${config.name} 已提交 Arena 生图请求，但没有识别到新图片"
        )
    }

    private suspend fun checkArenaImageBlocker(view: WebView) {
        val blocker = evaluate(view, """
            (() => {
              const visible=e=>{
                if(!e||e.offsetParent===null)return false;
                const s=getComputedStyle(e);
                return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)>0.05;
              };
              const dialog=[...document.querySelectorAll('[role="dialog"]')].find(visible);
              const dialogText=(dialog?.innerText||dialog?.textContent||'').toLowerCase();
              if(dialog&&/terms of use|privacy policy/.test(dialogText)&&
                [...dialog.querySelectorAll('button')].some(b=>/\bagree\b/i.test(b.innerText||''))){
                return 'terms';
              }
              const pageText=(document.body?.innerText||'').toLowerCase();
              const challenge=[...document.querySelectorAll(
                'iframe[title*="recaptcha challenge" i],iframe[src*="/recaptcha/api2/bframe" i]'
              )].some(visible)||/security verification|verify you are human|验证您是真人/.test(pageText);
              return challenge?'captcha':'';
            })()
        """.trimIndent())
        when (blocker) {
            "terms" -> throw WebAutomationException(
                "terms_required",
                "Arena.ai 首次使用需在浏览器点 Agree 同意条款"
            )
            "captcha" -> throw WebAutomationException(
                "captcha_required",
                "Arena.ai 触发 reCAPTCHA，请在浏览器人工完成验证后重试"
            )
        }
    }

    private suspend fun loadUrl(view: WebView, url: String) {
        suspendCancellableCoroutine { cont ->
            main.post {
                if (!cont.isActive) return@post
                view.loadUrl(url)
                cont.resume(Unit)
            }
        }
    }

    /**
     * Generic chat-page image generation. It intentionally relies on semantic
     * controls and newly rendered images so a newly added web chat does not need
     * an APK update or a domain-specific selector.
     */
    private suspend fun generateChatPageImage(
        view: WebView,
        config: CustomWebProviderConfig,
        prompt: String,
        ratio: String?,
    ): List<String> {
        val modeResult = evaluate(view, """
            (() => {
              const visible=e=>{
                if(!e||e.offsetParent===null)return false;
                const s=getComputedStyle(e),r=e.getBoundingClientRect();
                return s.display!=='none'&&s.visibility!=='hidden'&&
                  Number(s.opacity)>0.05&&r.width>24&&r.height>18;
              };
              const label=e=>(
                (e.innerText||'')+' '+(e.getAttribute('aria-label')||'')+' '+
                (e.getAttribute('title')||'')+' '+(e.getAttribute('data-action')||'')
              ).trim().toLowerCase();
              const exact=/^(图片生成|生成图片|文生图|ai绘画|image generation|generate image|create image)$/i;
              const candidates=[...document.querySelectorAll(
                'button,a,[role="button"],[role="tab"],[role="menuitem"]'
              )].filter(visible).filter(e=>exact.test(label(e)));
              const target=candidates.find(e=>
                e.getAttribute('aria-selected')!=='true'&&
                e.getAttribute('aria-pressed')!=='true'&&
                !/(^|\s)(active|selected|checked)(\s|$)/i.test(String(e.className))
              );
              if(!target)return candidates.length?'active':'missing';
              target.click();
              return 'clicked';
            })()
        """.trimIndent())
        if (modeResult == "clicked") delay(500)

        val baselineJson = evaluate(
            view,
            "JSON.stringify([...document.images].map(i=>i.currentSrc||i.src).filter(Boolean))"
        )
        val baseline = runCatching { JSONArray(baselineJson) }.getOrDefault(JSONArray()).let { array ->
            (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank).toSet()
        }
        val pageUrl = evaluate(view, "location.href||''").ifBlank { config.loginUrl }
        val requestedPrompt = buildString {
            append("请生成图片")
            ratio?.takeIf(String::isNotBlank)?.let { append("，画面比例为 ").append(it) }
            append("：\n").append(prompt)
        }
        val text = JSONObject.quote(requestedPrompt)
        val autoInput = """(()=>{const visible=e=>{if(!e||e.disabled||e.readOnly||e.offsetParent===null)return false;const s=getComputedStyle(e),r=e.getBoundingClientRect();return s.visibility!=='hidden'&&s.display!=='none'&&Number(s.opacity)>0.05&&r.width>80&&r.height>18};const score=e=>{const p=((e.getAttribute('placeholder')||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();let n=0;if(e.tagName==='TEXTAREA')n+=120;if(e.isContentEditable)n+=100;if(e.getAttribute('role')==='textbox')n+=80;if(/message|prompt|chat|ask|输入|消息|提问/.test(p))n+=90;if(e.getAttribute('role')==='combobox')n-=200;if(e.type==='search')n-=100;const r=e.getBoundingClientRect();n+=Math.min(40,r.top/innerHeight*40);n+=Math.min(20,r.width/innerWidth*20);return n};return [...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"],input[type="text"],input:not([type])')].filter(visible).sort((a,b)=>score(b)-score(a))[0]||null})()"""
        val input = configuredElementExpression(config.inputSelector, autoInput)
        val autoSend = """
            (() => {
              const input=$autoInput;
              const visible=e=>{
                if(!e||e.offsetParent===null)return false;
                const s=getComputedStyle(e),r=e.getBoundingClientRect();
                return s.visibility!=='hidden'&&s.display!=='none'&&
                  Number(s.opacity)>0.05&&r.width>20&&r.height>20;
              };
              const metadata=e=>(
                (e.id||'')+' '+(e.getAttribute('aria-label')||'')+' '+
                (e.getAttribute('title')||'')+' '+(e.getAttribute('data-testid')||'')+' '+
                (e.getAttribute('data-action')||'')+' '+(e.className||'')+' '+
                (e.innerText||'')+' '+(e.innerHTML||'')
              ).toLowerCase();
              const score=e=>{
                if(!visible(e)||e.getAttribute('role')==='switch')return -999;
                const h=metadata(e),r=e.getBoundingClientRect();
                let n=0;
                if(/lucide-send|icon-send|send|submit|generate|create|arrow.?up|paper.?plane|发送|提问|提交|生成|创作/.test(h))n+=220;
                if(/attach|upload|photo|mic|voice|tool|search|model|think|文件|语音|工具|搜索|模型|思考/.test(h))n-=180;
                if(e.type==='submit')n+=180;
                if(e.tagName==='BUTTON'||e.getAttribute('role')==='button')n+=25;
                if(input){
                  const ir=input.getBoundingClientRect();
                  const dx=Math.abs(r.left-ir.right);
                  const dy=Math.abs((r.top+r.bottom-ir.top-ir.bottom)/2);
                  n+=Math.max(0,140-(dx+dy)/2);
                  if(e.closest('form')&&e.closest('form')===input.closest('form'))n+=120;
                  if(input.parentElement?.contains(e))n+=70;
                }
                return n;
              };
              return [...document.querySelectorAll(
                'button,[role="button"],input[type="submit"],a,'+
                '[id*="send" i],[class*="send" i],[data-action*="send" i],'+
                '[aria-label*="发送"],[title*="发送"]'
              )].filter(visible).map(e=>({e,n:score(e)}))
                .sort((a,b)=>b.n-a.n).find(x=>x.n>140)?.e||null;
            })()
        """.trimIndent()
        val send = configuredElementExpression(config.sendSelector, autoSend)
        val inputDeadline = System.currentTimeMillis() + 30_000
        var filled = "input_not_found"
        while (filled == "input_not_found" && System.currentTimeMillis() < inputDeadline) {
            filled = evaluate(view, """
                (()=>{try{
                  const e=$input;
                  if(!e)return 'input_not_found';
                  e.focus();
                  if(e.tagName==='TEXTAREA'||e.tagName==='INPUT'){
                    const setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value')?.set;
                    if(setter)setter.call(e,$text);else e.value=$text;
                  }else{
                    e.textContent=$text;
                    const range=document.createRange();range.selectNodeContents(e);range.collapse(false);
                    const selection=getSelection();selection.removeAllRanges();selection.addRange(range);
                  }
                  e.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,inputType:'insertText',data:$text}));
                  e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:$text}));
                  e.dispatchEvent(new Event('change',{bubbles:true}));
                  return 'ok';
                }catch(x){return String(x)}})()
            """.trimIndent())
            if (filled == "input_not_found") delay(250)
        }
        if (filled != "ok") {
            throw WebAutomationException(
                "image_submit_failed",
                "${config.name} 生图输入失败：$filled"
            )
        }
        val sendDeadline = System.currentTimeMillis() + 5_000
        var submitted = false
        while (!submitted && System.currentTimeMillis() < sendDeadline) {
            submitted = evaluate(view, """
                (()=>{try{
                  const b=$send;
                  if(!b||b.disabled||b.getAttribute('aria-disabled')==='true')return false;
                  b.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));
                  b.click();
                  b.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));
                  return true;
                }catch(_){return false}})()
            """.trimIndent()) == "true"
            if (!submitted) delay(150)
        }
        if (!submitted) {
            submitted = evaluate(view, """
                (()=>{try{
                  const e=$input;
                  if(!e)return false;
                  const form=e.closest('form');
                  if(form?.requestSubmit){form.requestSubmit();return true}
                  e.focus();
                  for(const type of ['keydown','keypress','keyup']){
                    e.dispatchEvent(new KeyboardEvent(type,{
                      key:'Enter',code:'Enter',keyCode:13,which:13,
                      bubbles:true,cancelable:true
                    }));
                  }
                  return true;
                }catch(_){return false}})()
            """.trimIndent()) == "true"
        }
        if (!submitted) {
            throw WebAutomationException(
                "image_submit_failed",
                "${config.name} 未识别到发送控件，请在高级设置填写发送按钮 CSS"
            )
        }

        val imageCandidates = """
            (() => {
              const visible=e=>{
                if(!e||e.offsetParent===null)return false;
                const s=getComputedStyle(e),r=e.getBoundingClientRect();
                return s.display!=='none'&&s.visibility!=='hidden'&&
                  Number(s.opacity)>0.05&&r.width>96&&r.height>96;
              };
              const metadata=e=>{
                const parts=[e.alt||'',e.getAttribute('aria-label')||''];
                let node=e;
                for(let i=0;node&&i<7;i++,node=node.parentElement){
                  parts.push(
                    (node.id||'')+' '+(node.className||'')+' '+
                    (node.getAttribute?.('data-role')||'')+' '+
                    (node.getAttribute?.('data-testid')||'')+' '+
                    (node.getAttribute?.('data-message-author-role')||'')
                  );
                }
                return parts.join(' ').toLowerCase();
              };
              return [...document.images].map((image,index)=>{
                const src=image.currentSrc||image.src||'';
                const h=metadata(image);
                let score=0;
                if(/assistant|answer|response|bot|generated|result|bubble--ai|conv--ai|item--ai|message/.test(h))score+=180;
                if(/avatar|logo|icon|emoji|sticker|profile|qrcode|qr-code|头像|图标|表情|二维码/.test(h))score-=300;
                if(/human|bubble--human|conv--human|item--human|role.?user|message.?user|upload/.test(h))score-=260;
                if(image.naturalWidth>=512&&image.naturalHeight>=512)score+=160;
                else if(image.naturalWidth>=256&&image.naturalHeight>=256)score+=90;
                if(image.naturalWidth*image.naturalHeight>=150000)score+=80;
                if(image.closest('nav,aside,header,footer,[role="dialog"],form'))score-=250;
                return {src,index,score,w:image.naturalWidth,h:image.naturalHeight,
                  ready:visible(image)&&image.complete&&image.naturalWidth>=128&&image.naturalHeight>=128};
              }).filter(x=>x.ready&&x.src&&x.score>=120)
                .sort((a,b)=>a.index-b.index);
            })()
        """.trimIndent()
        val deadline = System.currentTimeMillis() + 240_000
        var lastFresh = emptyList<String>()
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val values = runCatching {
                JSONArray(evaluate(view, "JSON.stringify($imageCandidates)"))
            }.getOrDefault(JSONArray())
            val fresh = (0 until values.length()).mapNotNull { index ->
                values.optJSONObject(index)?.optString("src")
                    ?.takeIf { it.isNotBlank() && it !in baseline }
            }.distinct().takeLast(4)
            stable = if (fresh.isNotEmpty() && fresh == lastFresh) stable + 1 else 0
            lastFresh = fresh
            if (stable >= 3) {
                val cached = fresh.map { url ->
                    runCatching { cacheWebImage(url, pageUrl) }.getOrElse { error ->
                        LogStore.w("CustomWebImage", "图片缓存失败，返回网页地址：${error.message}")
                        url
                    }
                }
                LogStore.i("CustomWebImage", "${config.name} 通用生图完成，提取到 ${cached.size} 张图片")
                return cached
            }
        }
        throw WebAutomationException(
            "image_timeout",
            "${config.name} 已提交生图请求，但没有识别到新图片；可在高级设置确认该网页的输入与发送控件"
        )
    }

    private suspend fun cacheWebImage(url: String, referer: String): String {
        if (url.startsWith("data:image/", ignoreCase = true)) {
            return withContext(Dispatchers.IO) { GeneratedImageStore.saveDataUrl(url) }
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            throw WebAutomationException("image_download_failed", "网页返回了暂不支持的图片地址")
        }
        val cookies = suspendCancellableCoroutine { cont ->
            main.post {
                if (cont.isActive) {
                    cont.resume(CookieManager.getInstance().getCookie(url).orEmpty())
                }
            }
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                if (cookies.isNotBlank()) header("Cookie", cookies)
                if (referer.startsWith("http://") || referer.startsWith("https://")) {
                    header("Referer", referer)
                }
                header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                )
            }.build()
            imageClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw WebAutomationException(
                        "image_download_failed",
                        "网页图片下载失败 HTTP ${response.code}"
                    )
                }
                GeneratedImageStore.saveBytes(
                    response.body?.bytes() ?: ByteArray(0),
                    response.header("Content-Type")
                )
            }
        }
    }

    private suspend fun generateDddd(
        view: WebView,
        config: CustomWebProviderConfig,
        prompt: String,
    ): List<String> {
        val baseline = evaluate(
            view,
            "JSON.stringify([...document.querySelectorAll('#gallery .image-card img')].map(i=>i.currentSrc||i.src).filter(Boolean))"
        )
        val baselineSet = runCatching { JSONArray(baseline) }.getOrDefault(JSONArray()).let { array ->
            (0 until array.length()).map { array.optString(it) }.toSet()
        }
        val submitted = evaluate(view, """
            (()=>{try{
              const input=document.querySelector('#prompt');
              const button=document.querySelector('#generate-button');
              if(!input)return 'input_not_found';
              if(!button)return 'send_not_found';
              const setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(input),'value')?.set;
              if(setter)setter.call(input,${JSONObject.quote(prompt)});else input.value=${JSONObject.quote(prompt)};
              input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:${JSONObject.quote(prompt)}}));
              input.dispatchEvent(new Event('change',{bubbles:true}));
              if(button.disabled||button.getAttribute('aria-disabled')==='true')return 'send_not_ready';
              const form=document.querySelector('#generate-form');
              if(form?.requestSubmit)form.requestSubmit(button);else button.click();
              return 'ok';
            }catch(x){return String(x)}})()
        """.trimIndent())
        if (submitted != "ok") {
            throw WebAutomationException(
                "image_submit_failed",
                "${config.name} 生图提交失败：$submitted"
            )
        }

        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val state = evaluate(view, """
                (()=>JSON.stringify({
                  items:[...document.querySelectorAll('#gallery .image-card')].map(card=>{
                    const image=card.querySelector('img');
                    return {
                      preview:image?.currentSrc||image?.src||'',
                      source:card.querySelector('a[download]')?.href||image?.currentSrc||image?.src||'',
                      ready:!!image&&image.offsetParent!==null&&image.complete&&image.naturalWidth>128
                    };
                  }).filter(item=>item.ready&&item.preview&&item.source),
                  status:(document.querySelector('#form-status')?.innerText||'').trim(),
                  failed:[...document.querySelectorAll('#gallery .task-card.is-failed')]
                    .map(e=>(e.innerText||'').trim()).filter(Boolean)
                }))()
            """.trimIndent())
            val obj = runCatching { JSONObject(state) }.getOrDefault(JSONObject())
            val items = obj.optJSONArray("items") ?: JSONArray()
            val fresh = (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.takeIf {
                    it.optString("preview").isNotBlank() &&
                        it.optString("preview") !in baselineSet &&
                        it.optString("source").isNotBlank()
                }?.optString("source")
            }
                .distinct()
            if (fresh.isNotEmpty()) {
                val cached = fresh.take(4).map { cacheDdddImage(it) }
                LogStore.i("CustomWebImage", "${config.name} 生成完成，缓存并提取到 ${cached.size} 张图片")
                return cached
            }
            val failures = obj.optJSONArray("failed") ?: JSONArray()
            if (failures.length() > 0) {
                throw WebAutomationException(
                    "upstream_page_error",
                    failures.optString(failures.length() - 1).ifBlank { "${config.name} 生图失败" }
                )
            }
        }
        throw WebAutomationException("image_timeout", "${config.name} 网页生图等待超时")
    }

    private suspend fun cacheDdddImage(url: String): String {
        val cookies = suspendCancellableCoroutine { cont ->
            main.post {
                if (cont.isActive) {
                    cont.resume(CookieManager.getInstance().getCookie(url).orEmpty())
                }
            }
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                if (cookies.isNotBlank()) header("Cookie", cookies)
                header("Referer", "https://image.dddd.zone/")
                header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                )
            }.build()
            imageClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw WebAutomationException(
                        "image_download_failed",
                        "网页图片下载失败 HTTP ${response.code}"
                    )
                }
                GeneratedImageStore.saveBytes(
                    response.body?.bytes() ?: ByteArray(0),
                    response.header("Content-Type")
                )
            }
        }
    }

    private suspend fun generateLongCat(view: WebView, config: CustomWebProviderConfig, prompt: String, refImages: List<ChatAttachment> = emptyList()): List<String> {
        var mode = evaluate(view, """(()=>{const exact=t=>[...document.querySelectorAll('body *')].find(e=>e.offsetParent!==null&&(e.innerText||'').trim()===t);if(exact('图片生成')?.closest('.agent-skills'))return 'active';const item=exact('图片生成');if(item){(item.closest('.v-checked-button')||item).click();return 'clicked'}const more=exact('更多');if(more){const box=more.closest('[data-more="true"]');(box?.querySelector('.van-popover__wrapper')||box||more).click();return 'opened'}return 'missing'})()""")
        if (mode == "opened") {
            delay(800)
            mode = evaluate(view, """(()=>{const all=[...document.querySelectorAll('body *')].filter(e=>e.offsetParent!==null&&(e.innerText||'').trim()==='图片生成');const active=all.find(e=>e.closest('.agent-skills'));if(active)return 'active';const e=all[all.length-1];if(!e)return 'missing';(e.closest('.v-checked-button,[role="menuitem"]')||e).click();return 'clicked'})()""")
        }
        if (mode == "missing") throw WebAutomationException("image_mode_not_found", "${config.name} 图片生成模式不可用")
        delay(350)
        // 注入参考图（图生图）：Longcat 生图界面有 #file-upload-gen_image input
        if (refImages.isNotEmpty()) {
            val inj = runCatching { JSONObject(evaluate(view, longCatRefImageScript(refImages))) }.getOrDefault(JSONObject())
            if (!inj.optBoolean("ok")) {
                LogStore.w("CustomWebImage", "${config.name} 参考图注入失败: ${inj.optString("error", "unknown")}")
            } else {
                LogStore.i("CustomWebImage", "${config.name} 已注入 ${refImages.size} 张参考图")
                delay(600)  // 等网页解析参考图缩略图
            }
        }
        // Switching to image mode injects recommendation thumbnails. Capture the
        // baseline afterwards so those samples cannot be mistaken for this request.
        val baseline = evaluate(view, "JSON.stringify([...document.images].map(i=>i.currentSrc||i.src).filter(Boolean))")
        val baselineSet = runCatching { JSONArray(baseline) }.getOrDefault(JSONArray()).let { a ->
            (0 until a.length()).map { a.optString(it) }.toSet()
        }
        val submitted = evaluate(view, """
            (()=>{try{const e=document.querySelector('[contenteditable="true"][role="textbox"]');const b=document.querySelector('.send-btn');if(!e)return 'input_not_found';if(!b)return 'send_not_found';e.focus();e.innerHTML='<p></p>';e.querySelector('p').textContent=${JSONObject.quote(prompt)};e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:${JSONObject.quote(prompt)}}));b.click();return 'ok'}catch(x){return String(x)}})()
        """.trimIndent())
        if (submitted != "ok") throw WebAutomationException("image_submit_failed", "${config.name} 生图提交失败：$submitted")
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val values = runCatching { JSONArray(evaluate(view, "JSON.stringify([...document.images].filter(i=>i.offsetParent!==null&&i.naturalWidth>256).map(i=>i.currentSrc||i.src).filter(Boolean))")) }.getOrDefault(JSONArray())
            val fresh = (0 until values.length()).map { values.optString(it) }
                .filter { it.isNotBlank() && it !in baselineSet && !it.startsWith("data:") }
                .distinct()
            if (fresh.isNotEmpty()) return fresh.take(4)
        }
        throw WebAutomationException("image_timeout", "${config.name} 网页生图等待超时")
    }

    /** Longcat 生图参考图注入：往 #file-upload-gen_image input 注入 DataTransfer 文件。 */
    private fun longCatRefImageScript(refs: List<ChatAttachment>): String {
        val encoded = buildJsonArray {
            refs.forEach { item ->
                add(buildJsonObject {
                    put("name", item.name)
                    put("mime", item.mime)
                    put("data", item.dataUrl)
                })
            }
        }.toString()
        return """
            (()=>{try{
              const input=document.querySelector('#file-upload-gen_image')||document.querySelector('input[type="file"]');
              if(!input)return JSON.stringify({ok:false,error:'file_input_not_found'});
              const dt=new DataTransfer();
              for(const item of $encoded){
                const comma=item.data.indexOf(',');
                const bytes=atob(item.data.slice(comma+1));
                const data=new Uint8Array(bytes.length);
                for(let i=0;i<bytes.length;i++)data[i]=bytes.charCodeAt(i);
                dt.items.add(new File([data],item.name,{type:item.mime}));
              }
              input.files=dt.files;
              input.dispatchEvent(new Event('input',{bubbles:true}));
              input.dispatchEvent(new Event('change',{bubbles:true}));
              return JSON.stringify({ok:true,count:dt.files.length});
            }catch(e){return JSON.stringify({ok:false,error:String(e)})}})()
        """.trimIndent()
    }

    private suspend fun evaluate(view: WebView, script: String): String =
        withTimeoutOrNull(WEBVIEW_EVALUATE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                main.post {
                    if (!cont.isActive) return@post
                    if (!view.isAttachedToWindow) {
                        cont.resume("")
                        return@post
                    }
                    view.evaluateJavascript(script) { raw ->
                        if (cont.isActive) cont.resume(decode(raw))
                    }
                }
            }
        } ?: throw WebAutomationException(
            "webview_evaluate_timeout",
            "网页连接已中断，请在浏览器重新打开该网页后重试"
        )
    private fun decode(raw: String?): String = if (raw == null || raw == "null") "" else
        runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault(raw)

    private const val ARENA_IMAGE_URL = "https://arena.ai/image"
    private val ARENA_IMAGE_MODE_READY_SCRIPT = """
        (() => [...document.querySelectorAll('textarea')]
          .some(e=>/describe the image/i.test(e.getAttribute('placeholder')||'')))()
    """.trimIndent()
    private val ARENA_REJECTION_PATTERN =
        Regex("prompt failed|rate limit|too many requests", RegexOption.IGNORE_CASE)
}

private const val WEBVIEW_EVALUATE_TIMEOUT_MS = 15_000L
private const val BROWSER_AUTO_ATTACH_TIMEOUT_MS = 60_000L
