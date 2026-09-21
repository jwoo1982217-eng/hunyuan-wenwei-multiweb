package com.wenwei.deepseekproxy.proxy;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 网页中继子模型选择器（patch34/35 泛化版，2026-09-21）：支持多个网页供应商的
 * 页面模型选择 + 回复提取净化。
 *
 * 【供应商】文心（wenxin.baidu.com）7 个子模型中的 4 个 + 华为云码道
 * （devcloud.cn-north-4.huaweicloud.com）7 个模型。页面模型选中态只在 React state，
 * 必须走 UI：点菜单按钮开列表 → 点对应行（scrollIntoView + 实时坐标）→ 校验 chip 文本。
 *
 * 【四个 smali 入口（各插一条无分支 invoke）】
 *   1. expandModels(Iterable)  —— ProxyViewModel.refreshProviderRegistry 合并处：
 *      把各供应商主模型复制成子模型（id 加 --suffix，accountId 同主模型）。
 *   2. setPending(ProviderModel) —— ProxyServer.handleCustomWebChat 入口：
 *      记录本次请求的目标（供应商 + 菜单标题）。
 *   3. applyIfPending(WebView)  —— CustomWebChatAutomation.chat() awaitAttached 后：
 *      校验页面 URL 与供应商一致后，在页面上选中目标模型（幂等）。
 *   4. resolveResponseSelector(CharSequence,CharSequence) —— 配置 load 默认值：
 *      只抓回复正文容器（文心 .cosd-markdown-content / 华为云 .mc-markdown-render）。
 *
 * 【铁律】坐标 = CSS×density 不加屏幕偏移；真实 MotionEvent；点击前实时读坐标；
 * 失败只 Log.w 不抛（不能让切模型失败搞崩整条聊天）。
 */
public final class WebPageModelSelector {
    private static final String TAG = "WebPageModel";

    /** 供应商页面配置 */
    private static final class Cfg {
        final String host;          // 页面 URL 需包含（小写）
        final String idMarker;      // 主模型 publicModelId 需包含（小写）
        final String chipSel;       // chip 元素选择器
        final String menuBtnSel;    // 打开模型菜单的按钮
        final String rowSel;        // 菜单行容器选择器
        final String titleSel;      // 行内标题选择器
        final boolean rowExcludeTitle; // 行选择器是否误配标题元素（需排除 class 含 title）
        final String thinkTitle;    // 需要配套开关的模型菜单标题（null=无）
        final String thinkBtnText;  // 开关按钮文本（JS 里用 unicode 转义）
        final String respSel;       // 回复正文选择器默认值
        final String[][] models;    // {suffix, 菜单标题, chip 显示名}

        Cfg(String host, String idMarker, String chipSel, String menuBtnSel, String rowSel,
            String titleSel, boolean rowExcludeTitle, String thinkTitle, String thinkBtnText,
            String respSel, String[][] models) {
            this.host = host; this.idMarker = idMarker; this.chipSel = chipSel;
            this.menuBtnSel = menuBtnSel; this.rowSel = rowSel; this.titleSel = titleSel;
            this.rowExcludeTitle = rowExcludeTitle; this.thinkTitle = thinkTitle;
            this.thinkBtnText = thinkBtnText; this.respSel = respSel; this.models = models;
        }
    }

    private static final Cfg WENXIN = new Cfg(
            "wenxin.baidu.com", "wenxin",
            ".capsules-model-list-button",
            ".capsules-item-tag-model-arrow",
            "[class*=\"_model-list-item_\"]",
            "[class*=\"_model-list-item-title_\"]",
            true, "DeepSeek-R1", "\\u6df1\\u5ea6\\u601d\\u8003",
            ".cosd-markdown-content",
            new String[][]{
                    {"wx51", "文心5.1", "文心5.1"},
                    {"dsv4pro", "DeepSeek-V4 Pro", "DS-V4 Pro"},
                    {"dsv4flash", "DeepSeek-V4 Flash", "DS-V4 Flash"},
                    {"dsr1", "DeepSeek-R1", "DS-R1"},
            });

    private static final Cfg HUAWEI = new Cfg(
            "huaweicloud.com", "huaweicloud",
            ".tool-btn__inner",
            "button.tool-btn[title=\"\\u6a21\\u578b\"]",
            ".model-selector__item",
            ".model-selector__item-label",
            false, null, null,
            ".mc-markdown-render",
            new String[][]{
                    {"dsv4flash", "deepseek-v4-flash-0731", "deepseek-v4-flash-0731"},
                    {"glm53flash", "glm-5.3-flash", "glm-5.3-flash"},
                    {"dsv4pro", "deepseek-v4-pro-0813", "deepseek-v4-pro-0813"},
                    {"glm52", "GLM-5.2", "GLM-5.2"},
                    {"glm52arkts", "GLM-5.2-ArkTS-SPARK", "GLM-5.2-ArkTS-SPARK"},
                    {"pangu2pro", "OpenPangu-2.0-Pro", "OpenPangu-2.0-Pro"},
                    {"pangu2flash", "OpenPangu-2.0-Flash", "OpenPangu-2.0-Flash"},
            });

    private static final Cfg[] CFGS = {WENXIN, HUAWEI};

    private static volatile Cfg pendingCfg;
    private static volatile String pendingTitle;

    private WebPageModelSelector() {}

    // ==================== 1) 模型注册 ====================

    /** 在合并结果里给每个已知供应商追加子模型（无主模型则原样返回）。 */
    public static Iterable<ProviderModel> expandModels(Iterable<ProviderModel> models) {
        try {
            List<ProviderModel> out = new ArrayList<ProviderModel>();
            for (ProviderModel m : models) {
                if (m == null) continue;
                out.add(m);
            }
            for (Cfg cfg : CFGS) {
                ProviderModel template = null;
                for (ProviderModel m : out) {
                    if (m == null || m.getProviderType() != WebProviderType.CUSTOM_WEB) continue;
                    if (isPrimary(m, cfg)) { template = m; break; }
                }
                if (template == null) continue;
                for (int i = 0; i < cfg.models.length; i++) {
                    String id = template.getPublicModelId() + "--" + cfg.models[i][0];
                    out.add(new ProviderModel(id, cfg.models[i][1], WebProviderType.CUSTOM_WEB,
                            template.getCapabilities(), true, id, template.getAccountId()));
                }
                android.util.Log.i(TAG, "expandModels: +" + cfg.models.length + " models (" + cfg.host + ")");
            }
            return out;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "expandModels error: " + t);
            return models;
        }
    }

    /** 主模型：CUSTOM_WEB + id 含供应商特征 + 无 -- 后缀。 */
    private static boolean isPrimary(ProviderModel m, Cfg cfg) {
        try {
            if (m.getProviderType() != WebProviderType.CUSTOM_WEB) return false;
            String id = m.getPublicModelId();
            if (id == null) return false;
            id = id.toLowerCase(Locale.US);
            return id.contains(cfg.idMarker) && !id.contains("--");
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 2) 记录目标 ====================

    /** 从请求的 publicModelId 解析目标（供应商 + 菜单标题）；非已知子模型 → 清空。 */
    public static void setPending(ProviderModel model) {
        try {
            String id = (model == null) ? null : model.getPublicModelId();
            for (Cfg cfg : CFGS) {
                if (id != null) {
                    String low = id.toLowerCase(Locale.US);
                    if (low.contains(cfg.idMarker) && low.contains("--")) {
                        String suffix = low.substring(low.lastIndexOf("--") + 2);
                        for (int k = 0; k < cfg.models.length; k++) {
                            if (cfg.models[k][0].equals(suffix)) {
                                pendingCfg = cfg;
                                pendingTitle = cfg.models[k][1];
                                return;
                            }
                        }
                    }
                }
            }
            pendingCfg = null;
            pendingTitle = null;
        } catch (Throwable t) {
            pendingCfg = null;
            pendingTitle = null;
        }
    }

    // ==================== 3) 页面选中 ====================

    /** chat() 内 awaitAttached 之后调用；无 pending 立即返回。 */
    public static void applyIfPending(WebView view) {
        Cfg cfg = pendingCfg;
        String target = pendingTitle;
        pendingCfg = null;
        pendingTitle = null;
        if (cfg == null || target == null || view == null) return;
        try {
            if (!isPageFor(view, cfg)) {
                android.util.Log.w(TAG, "page mismatch (" + cfg.host + "), skip " + target);
                return;
            }
            select(view, cfg, target);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "applyIfPending error: " + t);
        }
    }

    /** 页面 URL 需包含供应商 host（主线程读 URL，拿不到不阻塞）。 */
    private static boolean isPageFor(WebView view, Cfg cfg) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] url = new String[1];
        view.post(new Runnable() {
            @Override
            public void run() {
                try { url[0] = view.getUrl(); } catch (Throwable ignored) { }
                latch.countDown();
            }
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            return true;
        }
        String u = url[0];
        if (u == null) return true;
        return u.toLowerCase(Locale.US).contains(cfg.host);
    }

    private static void select(WebView view, Cfg cfg, String target) throws InterruptedException {
        final String expectChip = chipFor(cfg, target);
        final boolean wantThink = cfg.thinkTitle != null && cfg.thinkTitle.equals(target);
        // 1) 等页面就绪：chip 出现（最多 20s）
        String chip = "";
        for (int i = 0; i < 40; i++) {
            chip = jsString(view, chipScript(cfg));
            if (chip != null && chip.length() > 0) break;
            Thread.sleep(500L);
        }
        if (chip == null || chip.length() == 0) {
            android.util.Log.w(TAG, "chip not ready, skip " + target);
            return;
        }
        // 2) 附加开关对齐（文心 R1 需开深度思考；其他模型关）
        if (cfg.thinkTitle != null) {
            ensureThink(view, cfg, wantThink);
        }
        // 3) chip 已是目标 → 完成
        chip = jsString(view, chipScript(cfg));
        if (chip != null && (chip.equals(expectChip) || chip.equals(target))) {
            android.util.Log.i(TAG, "already on " + chip + " (" + cfg.host + ")");
            return;
        }
        // 4) 开菜单 → 点项 → 校验 chip（4 轮）
        for (int attempt = 0; attempt < 4; attempt++) {
            String item = jsString(view, itemScript(cfg, target));
            if (item != null && item.length() > 0) {
                android.util.Log.i(TAG, "tap item " + target + " @" + item + " attempt=" + attempt);
                tapPoint(view, item);
                Thread.sleep(1300L);
                String now = jsString(view, chipScript(cfg));
                if (now != null && (now.equals(expectChip) || now.equals(target))) {
                    android.util.Log.i(TAG, "selected " + target + " chip=" + now + " (" + cfg.host + ")");
                    return;
                }
                continue;
            }
            String btn = jsString(view, menuBtnScript(cfg));
            if (btn != null && btn.length() > 0) {
                android.util.Log.i(TAG, "tap menu btn @" + btn + " attempt=" + attempt);
                tapPoint(view, btn);
                Thread.sleep(1200L);
            } else {
                Thread.sleep(700L);
            }
        }
        android.util.Log.w(TAG, "failed to select " + target + " (" + cfg.host + ")");
    }

    /** 把附加开关（如文心"深度思考"）切到目标态；已是目标态直接返回。 */
    private static void ensureThink(WebView view, Cfg cfg, boolean want) throws InterruptedException {
        String stScript = "(function(){try{"
                + "var c=[...document.querySelectorAll('[class*=\"capsules-model-button\"]')]"
                + ".find(function(x){return (x.innerText||'').trim()==='" + cfg.thinkBtnText + "';});"
                + "if(!c)return '';"
                + "return /active/.test(String(c.className))?'1':'0';}catch(e){return ''}})()";
        String ptScript = "(function(){try{"
                + "var c=[...document.querySelectorAll('[class*=\"capsules-model-button\"]')]"
                + ".find(function(x){return (x.innerText||'').trim()==='" + cfg.thinkBtnText + "';});"
                + "if(!c||c.offsetParent===null)return '';"
                + "var r=c.getBoundingClientRect();"
                + "if(r.width<=0||r.height<=0)return '';"
                + "return (Math.round(r.x+r.width/2))+','+(Math.round(r.y+r.height/2));}catch(e){return ''}})()";
        for (int attempt = 0; attempt < 3; attempt++) {
            String st = jsString(view, stScript);
            if ("1".equals(st) && want) return;
            if ("0".equals(st) && !want) return;
            String pt = jsString(view, ptScript);
            if (pt == null || pt.isEmpty()) {
                Thread.sleep(700L);
                continue;
            }
            android.util.Log.i(TAG, "tap think @" + pt + " want=" + want + " attempt=" + attempt);
            tapPoint(view, pt);
            Thread.sleep(1200L);
        }
        android.util.Log.w(TAG, "think align failed want=" + want);
    }

    private static String chipFor(Cfg cfg, String target) {
        for (int i = 0; i < cfg.models.length; i++) {
            if (cfg.models[i][1].equals(target)) return cfg.models[i][2];
        }
        return target;
    }

    // ==================== JS 脚本 ====================

    private static String chipScript(Cfg cfg) {
        return "(function(){try{var e=document.querySelector('" + cfg.chipSel + "');"
                + "if(!e||e.offsetParent===null)return '';"
                + "return (e.innerText||e.textContent||'').trim();}catch(e){return ''}})()";
    }

    private static String menuBtnScript(Cfg cfg) {
        return "(function(){try{var e=document.querySelector('" + cfg.menuBtnSel + "');"
                + "if(!e||e.offsetParent===null)return '';"
                + "var r=e.getBoundingClientRect();"
                + "if(r.width<=0||r.height<=0)return '';"
                + "return (Math.round(r.x+r.width/2))+','+(Math.round(r.y+r.height/2));}catch(e){return ''}})()";
    }

    private static String itemScript(Cfg cfg, String title) {
        String excl = cfg.rowExcludeTitle
                ? "var cn=String(row.className||'');if(cn.indexOf('title')>=0)continue;"
                : "";
        return "(function(){try{"
                + "var want=" + org.json.JSONObject.quote(title) + ";"
                + "var rows=document.querySelectorAll('" + cfg.rowSel + "');"
                + "for(var k=0;k<rows.length;k++){var row=rows[k];"
                + "if(row.offsetParent===null)continue;"
                + excl
                + "var t=row.querySelector('" + cfg.titleSel + "');"
                + "var txt=t?((t.innerText||t.textContent||'').trim()):'';"
                + "if(txt!==want)continue;"
                + "try{row.scrollIntoView({block:'center'})}catch(_){}"
                + "var r=row.getBoundingClientRect();"
                + "if(r.width<=0||r.height<=0)continue;"
                + "return (Math.round(r.x+r.width/2))+','+(Math.round(r.y+r.height/2));}"
                + "return '';}catch(e){return ''}})()";
    }

    // ==================== 4) 回复提取净化默认值 ====================

    /**
     * 配置 load() 里 ifBlank 之后调用。参数用 CharSequence：
     * 汇合点寄存器合并类型是 CharSequence（:goto_N 处 check-cast 过），用 String 会被 ART 拒。
     */
    public static String resolveResponseSelector(CharSequence loginUrl, CharSequence current) {
        try {
            if ((current == null || current.length() == 0) && loginUrl != null) {
                String u = loginUrl.toString().toLowerCase(Locale.US);
                for (Cfg cfg : CFGS) {
                    if (u.contains(cfg.host)) return cfg.respSel;
                }
            }
            return current == null ? "" : current.toString();
        } catch (Throwable t) {
            return current == null ? "" : current.toString();
        }
    }

    // ==================== 触摸 / JS 原语 ====================

    /** 主线程真实 MotionEvent（DOWN→sleep 60ms→UP，UP 带 +3px 偏移）；坐标 = CSS×density，不加屏幕偏移。 */
    private static void tapPoint(WebView view, String xy) {
        try {
            String[] parts = xy.split(",");
            final float x = Float.parseFloat(parts[0].trim());
            final float y = Float.parseFloat(parts[1].trim());
            final CountDownLatch latch = new CountDownLatch(1);
            view.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        float density = view.getResources().getDisplayMetrics().density;
                        float sx = x * density;
                        float sy = y * density;
                        long downTime = SystemClock.uptimeMillis();
                        view.dispatchTouchEvent(makeMotion(MotionEvent.ACTION_DOWN, sx, sy, downTime));
                        Thread.sleep(60L);
                        view.dispatchTouchEvent(makeMotion(MotionEvent.ACTION_UP, sx + 3f, sy + 3f, downTime));
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                }
            });
            latch.await(4, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    private static MotionEvent makeMotion(int action, float x, float y, long downTime) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = 1f;
        pc.size = 1f;
        return MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{pp},
                new MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /** 主线程 evaluateJavascript + 同步等待（去掉 JSON 引号包裹）；5s 超时返回 null。 */
    private static String jsString(WebView view, final String script) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        view.post(new Runnable() {
            @Override
            public void run() {
                try {
                    view.evaluateJavascript(script, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            out[0] = unquote(value);
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException ignored) {
            return null;
        }
        return out[0];
    }

    private static String unquote(String v) {
        if (v == null) return null;
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            String s = v.substring(1, v.length() - 1);
            s = s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\/", "/");
            return s;
        }
        return v;
    }
}
