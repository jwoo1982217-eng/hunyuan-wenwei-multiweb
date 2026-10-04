package com.wenwei.deepseekproxy.proxy

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

/**
 * 自定义网页供应商的页内模型选择器（移植自墨🐳 v1.0.13 WebPageModelSelector.kt）。
 * 对文心（wenxin.baidu.com）和华为云（huaweicloud.com）两类页面：
 *  - expandModels：把主模型展开成 `主模型--短名` 的多模型变体；
 *  - setPending/applyIfPending：聊天开始前登记目标模型，页面就绪后真实触摸点选；
 *  - resolveResponseSelector：这两类站点的回包选择器兜底。
 * 模型表 [Cfg.models]：短名 / 显示名 / 页面 chip 文本。
 */
object WebPageModelSelector {
    private const val TAG = "WebPageModel"

    private class Cfg(
        val host: String,
        val idMarker: String,
        val chipSel: String,
        val menuBtnSel: String,
        val rowSel: String,
        val titleSel: String,
        val rowExcludeTitle: Boolean,
        val thinkTitle: String?,
        val thinkBtnText: String?,
        val respSel: String,
        val models: Array<Array<String>>,
    )

    private val WENXIN = Cfg(
        "wenxin.baidu.com", "wenxin",
        ".capsules-model-list-button", ".capsules-item-tag-model-arrow",
        "[class*=\"_model-list-item_\"]", "[class*=\"_model-list-item-title_\"]",
        true, "DeepSeek-R1", "深度思考", ".cosd-markdown-content",
        arrayOf(
            arrayOf("wx51", "文心5.1", "文心5.1"),
            arrayOf("dsv4pro", "DeepSeek-V4 Pro", "DS-V4 Pro"),
            arrayOf("dsv4flash", "DeepSeek-V4 Flash", "DS-V4 Flash"),
            arrayOf("dsr1", "DeepSeek-R1", "DS-R1"),
        ),
    )

    private val HUAWEI = Cfg(
        "huaweicloud.com", "huaweicloud",
        ".tool-btn__inner", "button.tool-btn[title=\"模型\"]",
        ".model-selector__item", ".model-selector__item-label",
        false, null, null, ".mc-markdown-render",
        arrayOf(
            arrayOf("dsv4flash", "deepseek-v4-flash-0731", "deepseek-v4-flash-0731"),
            arrayOf("glm53flash", "glm-5.3-flash", "glm-5.3-flash"),
            arrayOf("dsv4pro", "deepseek-v4-pro-0813", "deepseek-v4-pro-0813"),
            arrayOf("glm52", "GLM-5.2", "GLM-5.2"),
            arrayOf("glm52arkts", "GLM-5.2-ArkTS-SPARK", "GLM-5.2-ArkTS-SPARK"),
            arrayOf("pangu2pro", "OpenPangu-2.0-Pro", "OpenPangu-2.0-Pro"),
            arrayOf("pangu2flash", "OpenPangu-2.0-Flash", "OpenPangu-2.0-Flash"),
        ),
    )

    private val CFGS = arrayOf(WENXIN, HUAWEI)

    @Volatile private var pendingCfg: Cfg? = null
    @Volatile private var pendingTitle: String? = null

    /** 主模型 → 变体模型展开（仅 CUSTOM_WEB；主模型 id 含 idMarker 且不带 --） */
    fun expandModels(models: List<ProviderModel>): List<ProviderModel> = runCatching {
        val out = models.toMutableList()
        for (cfg in CFGS) {
            val primary = out.firstOrNull {
                it.providerType == WebProviderType.CUSTOM_WEB && isPrimary(it, cfg)
            } ?: continue
            for (m in cfg.models) {
                val id = "${primary.publicModelId}--${m[0]}"
                out.add(ProviderModel(
                    id, m[1], WebProviderType.CUSTOM_WEB,
                    primary.capabilities, true, id, primary.accountId,
                ))
            }
            Log.i(TAG, "expandModels: +${cfg.models.size} models (${cfg.host})")
        }
        out
    }.getOrDefault(models)

    /** 聊天请求进来时登记要选的页内模型（按 `--短名` 后缀匹配） */
    fun setPending(model: ProviderModel?) {
        val id = runCatching { model?.publicModelId }.getOrNull()
        if (id == null) { pendingCfg = null; pendingTitle = null; return }
        for (cfg in CFGS) {
            val lower = id.lowercase(Locale.US)
            if (lower.contains(cfg.idMarker) && lower.contains("--")) {
                val short = lower.substring(lower.lastIndexOf("--") + 2)
                cfg.models.firstOrNull { it[0] == short }?.let { m ->
                    pendingCfg = cfg
                    pendingTitle = m[1]
                    return
                }
            }
        }
        pendingCfg = null
        pendingTitle = null
    }

    /** 页面就绪后调用：命中已登记配置则执行页内点选 */
    suspend fun applyIfPending(webView: WebView?) {
        val cfg = pendingCfg ?: return
        val title = pendingTitle ?: return
        pendingCfg = null
        pendingTitle = null
        if (webView == null) return
        runCatching {
            if (isPageFor(webView, cfg)) select(webView, cfg, title)
            else Log.w(TAG, "page mismatch (${cfg.host}), skip $title")
        }.onFailure { Log.e(TAG, "applyIfPending error: $it") }
    }

    /** 站点响应选择器兜底：命中已知站点时覆盖配置里的选择器 */
    fun resolveResponseSelector(loginUrl: CharSequence?, fallback: CharSequence?): String {
        if (fallback != null && fallback.isNotEmpty()) return fallback.toString()
        val lower = loginUrl?.toString()?.lowercase(Locale.US) ?: return fallback?.toString().orEmpty()
        for (cfg in CFGS) {
            if (lower.contains(cfg.host)) return cfg.respSel
        }
        return fallback?.toString().orEmpty()
    }

    private fun isPrimary(model: ProviderModel, cfg: Cfg): Boolean = runCatching {
        if (model.providerType != WebProviderType.CUSTOM_WEB) return@runCatching false
        val lower = model.publicModelId.lowercase(Locale.US)
        lower.contains(cfg.idMarker) && !lower.contains("--")
    }.getOrDefault(false)

    private fun chipFor(cfg: Cfg, title: String): String =
        cfg.models.firstOrNull { it[1] == title }?.get(2) ?: title

    private suspend fun select(webView: WebView, cfg: Cfg, title: String) {
        val want = chipFor(cfg, title)
        val thinkOn = cfg.thinkTitle != null && cfg.thinkTitle == title
        var chip = ""
        repeat(40) {
            chip = jsString(webView, chipScript(cfg)) ?: ""
            if (chip.isNotEmpty()) return@repeat
            delay(500)
        }
        if (chip.isEmpty()) { Log.w(TAG, "chip not ready, skip $title"); return }
        if (cfg.thinkTitle != null && cfg.thinkBtnText != null) {
            ensureThink(webView, cfg, thinkOn)
        }
        val current = jsString(webView, chipScript(cfg))
        if (current != null && (current == want || current == title)) {
            Log.i(TAG, "already on $current (${cfg.host})")
            return
        }
        repeat(4) { attempt ->
            val item = jsString(webView, itemScript(cfg, title))
            if (item.isNullOrBlank()) {
                val menu = jsString(webView, menuBtnScript(cfg))
                if (menu.isNullOrBlank()) {
                    delay(700)
                } else {
                    Log.i(TAG, "tap menu btn @$menu attempt=$attempt")
                    tapPoint(webView, menu)
                    delay(1200)
                }
            } else {
                Log.i(TAG, "tap item $title @$item attempt=$attempt")
                tapPoint(webView, item)
                delay(1300)
                val after = jsString(webView, chipScript(cfg))
                if (after != null && (after == want || after == title)) {
                    Log.i(TAG, "selected $title chip=$after (${cfg.host})")
                    return
                }
            }
        }
        Log.w(TAG, "failed to select $title (${cfg.host})")
    }

    private suspend fun ensureThink(webView: WebView, cfg: Cfg, want: Boolean) {
        val stateJs = "(function(){try{var c=[...document.querySelectorAll('[class*=\"capsules-model-button\"]')].find(function(x){return (x.innerText||'').trim()==='${cfg.thinkBtnText}';});if(!c)return '';return /active/.test(String(c.className))?'1':'0';}catch(e){return ''}})()"
        val posJs = "(function(){try{var c=[...document.querySelectorAll('[class*=\"capsules-model-button\"]')].find(function(x){return (x.innerText||'').trim()==='${cfg.thinkBtnText}';});if(!c||c.offsetParent===null)return '';var r=c.getBoundingClientRect();if(r.width<=0||r.height<=0)return '';return (Math.round(r.x+r.width/2))+','+(Math.round(r.y+r.height/2));}catch(e){return ''}})()"
        repeat(3) { attempt ->
            when (jsString(webView, stateJs)) {
                "1" -> if (want) return
                "0" -> if (!want) return
            }
            val pos = jsString(webView, posJs)
            if (pos.isNullOrBlank()) {
                delay(700)
            } else {
                Log.i(TAG, "tap think @$pos want=$want attempt=$attempt")
                tapPoint(webView, pos)
                delay(1200)
            }
        }
        Log.w(TAG, "think align failed want=$want")
    }

    private fun chipScript(cfg: Cfg) =
        "(function(){try{var e=document.querySelector('${cfg.chipSel}');if(!e||e.offsetParent===null)return '';return (e.innerText||e.textContent||'').trim();}catch(e){return ''}})()"

    private fun menuBtnScript(cfg: Cfg) =
        "(function(){try{var e=document.querySelector('${cfg.menuBtnSel}');if(!e||e.offsetParent===null)return '';var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return '';return (Math.round(r.x+r.width/2))+','+(Math.round(r.y+r.height/2));}catch(e){return ''}})()"

    private fun itemScript(cfg: Cfg, title: String): String {
        val exclude = if (cfg.rowExcludeTitle) "var cn=String(row.className||'');if(cn.indexOf('title')>=0)continue;" else ""
        return "(function(){try{var want=" + JSONObject.quote(title) + ";var rows=document.querySelectorAll('" + cfg.rowSel + "');for(var k=0;k<rows.length;k++){var row=rows[k];if(row.offsetParent===null)continue;" + exclude + "var t=row.querySelector('" + cfg.titleSel + "');var txt=t?((t.innerText||t.textContent||'').trim()):'';if(txt!==want)continue;try{row.scrollIntoView({block:'center'})}catch(_){}var r=row.getBoundingClientRect();if(r.width<=0||r.height<=0)continue;return (Math.round(r.x+r.width/2))+','+(Math.round(r.y+r.height/2));}return '';}catch(e){return ''}})()"
    }

    private suspend fun jsString(webView: WebView, script: String): String? {
        var result: String? = null
        runCatching {
            webView.post {
                runCatching {
                    webView.evaluateJavascript(script) { value -> result = unquote(value) }
                }
            }
        }
        // evaluateJavascript 回调通常几十毫秒内返回
        repeat(25) {
            if (result != null) return result
            delay(200)
        }
        return result
    }

    private suspend fun tapPoint(webView: WebView, xy: String) {
        runCatching {
            val parts = xy.split(",").map { it.trim().toFloat() }
            val cssX = parts[0]; val cssY = parts[1]
            webView.post {
                runCatching {
                    val density = webView.resources.displayMetrics.density
                    val x = cssX * density
                    val y = cssY * density
                    val now = SystemClock.uptimeMillis()
                    webView.dispatchTouchEvent(makeMotion(MotionEvent.ACTION_DOWN, x, y, now))
                    Thread.sleep(60)
                    webView.dispatchTouchEvent(makeMotion(MotionEvent.ACTION_UP, x + 3f, y + 3f, now))
                }
            }
            delay(700)
        }
    }

    private fun makeMotion(action: Int, x: Float, y: Float, downTime: Long): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f; size = 1f
        })
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 4098, 0)
    }

    private fun isPageFor(webView: WebView, cfg: Cfg): Boolean = runCatching {
        webView.url?.lowercase(Locale.US)?.contains(cfg.host) ?: true
    }.getOrDefault(true)

    private fun unquote(s: String?): String? {
        if (s == null) return null
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
            return s.substring(1, s.length - 1)
                .replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\/", "/")
        }
        return s
    }
}
