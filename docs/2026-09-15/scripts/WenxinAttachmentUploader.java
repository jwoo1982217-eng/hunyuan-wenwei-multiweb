package com.wenwei.deepseekproxy.proxy;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 文心（wenxin.baidu.com）附件上传 + 心跳发送（2026-09-14 用户验证版）。
 *
 * 【可注入附件的稳定路径（用户手动操作 + App 实测均通过）】
 *   真实触摸 "+" → 菜单弹出 → 真实触摸"相册" → WebView 原生 onShowFileChooser
 *   → NativeWebFileChooserBridge 喂文件 → 页面自走上传 → 附件 chip 出现。
 *   （JS DataTransfer 注入对文心无效：isTrusted=false 被忽略。）
 *
 * 【心跳发送（本次新增）】
 *   文心是 React 站点，合成 click 无效，必须真实触摸发送按钮。
 *   后台线程：等"附件 chip 就绪 + 输入框有文字" → 心跳：读发送按钮中心 →
 *   真实触摸 → 校验输入框清空 → 重试（12 拍）。
 *
 * 【坐标模型（实测校准）】dispatchTouchEvent 用 WebView 局部坐标 = CSS × density。
 */
public final class WenxinAttachmentUploader {
    private static final String TAG = "WenxinUpload";

    private WenxinAttachmentUploader() {}

    /** 上传附件（触摸路径）；同时无条件启动心跳发送线程。返回 true 表示 chip 就绪。 */
    public static boolean upload(WebView view, List attachments) {
        try {
            if (view == null || attachments == null || attachments.isEmpty()) return false;

            // 无条件启动心跳发送（等 chip + 文字就绪后触摸发送）
            startAutoSubmit(view);

            // 扩容 resource timing buffer（默认 250 上限，满了新请求不进 buffer）
            jsString(view, "(function(){try{performance.setResourceTimingBufferSize(2000);return 'ok';}catch(e){return ''}})()");

            return touchInject(view, attachments);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "upload error: " + t);
            return false;
        }
    }

    /**
     * 生图参考图注入专用（仅注入，不启动心跳发送——生图的发送由生图流程自身处理）。
     * 通过反射从 CustomWebImageAutomation.attached 拿 WebView；仅当页面是文心时执行，
     * 且立即返回（实际注入跑后台线程），避免阻塞生图协程。
     */
    /** 图生视频：暂存 refs（generateVideo 早期调用，此时 refImages 寄存器活跃）。 */
    private static volatile List stashedVideoRefs;

    public static void stashVideoRefs(com.wenwei.deepseekproxy.proxy.CustomWebProviderConfig config, List refs) {
        try {
            if (config == null || refs == null || refs.isEmpty()) return;
            String loginUrl = config.getLoginUrl();
            if (loginUrl == null || !loginUrl.contains("wenxin")) return;
            stashedVideoRefs = refs;
            android.util.Log.i(TAG, "stashVideoRefs: refs=" + refs.size());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "stashVideoRefs error: " + t);
        }
    }

    /** 图生视频：注入参考图（:cond_4 入口无参调用；内部反射拿 attached WebView）。 */
    public static void injectVideoRefs() {
        try {
            final List refs = stashedVideoRefs;
            if (refs == null || refs.isEmpty()) return;
            stashedVideoRefs = null;
            injectRefsAuto(refs);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "injectVideoRefs error: " + t);
        }
    }

    public static void injectRefsAuto(List refs) {
        try {
            if (refs == null || refs.isEmpty()) return;
            Class<?> cls = Class.forName("com.wenwei.deepseekproxy.proxy.CustomWebImageAutomation");
            java.lang.reflect.Field f = cls.getDeclaredField("attached");
            f.setAccessible(true);
            Object attached = f.get(null);
            if (attached == null) {
                android.util.Log.i(TAG, "injectRefsAuto: no attached view");
                return;
            }
            Object viewObj = attached.getClass().getMethod("getFirst").invoke(attached);
            if (!(viewObj instanceof WebView)) {
                android.util.Log.i(TAG, "injectRefsAuto: attached.first not WebView");
                return;
            }
            final WebView view = (WebView) viewObj;
            // 主线程取 URL（WebView.getUrl 必须在主线程）
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] urlHolder = new String[1];
            view.post(new Runnable() {
                @Override
                public void run() {
                    try { urlHolder[0] = view.getUrl(); } catch (Throwable ignored) { }
                    latch.countDown();
                }
            });
            latch.await(3, TimeUnit.SECONDS);
            String url = urlHolder[0];
            if (url == null || !url.contains("wenxin.baidu.com")) return;
            android.util.Log.i(TAG, "injectRefsAuto: wenxin image-gen refs=" + refs.size());
            // 同步执行（与 chat 同序：先菜单交互，再 JS 兜底注入，再等 chip）——
            // 生图流程会阻塞等待本次调用返回，确保 chip 就绪后才继续发送。
            jsString(view, "(function(){try{performance.setResourceTimingBufferSize(2000);return 'ok';}catch(e){return ''}})()");
            touchInject(view, refs);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "injectRefsAuto error: " + t);
        }
    }

    /** 触摸注入核心：prepare → 触摸"+" → 触摸"相册" → 等 chip；失败走 JS 兜底注入。 */
    private static boolean touchInject(WebView view, List attachments) {
        try {
            // 1) prepare（onShowFileChooser 到达时自动喂文件）
            Class<?> bridge = Class.forName("com.wenwei.deepseekproxy.proxy.NativeWebFileChooserBridge");
            Object inst = bridge.getField("INSTANCE").get(null);
            Object ok = bridge.getMethod("prepare", List.class).invoke(inst, attachments);
            if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                android.util.Log.w(TAG, "prepare failed");
                return jsFallback(view, attachments);
            }
            android.util.Log.i(TAG, "prepare ok size=" + attachments.size());

            // 2) 打开菜单（最多 3 次重试；每次点击前实时读坐标）
            boolean menuOpen = false;
            for (int attempt = 0; attempt < 3 && !menuOpen; attempt++) {
                String plus = jsString(view, PLUS_SCRIPT);
                if (plus == null || plus.isEmpty()) {
                    android.util.Log.w(TAG, "plus button not found (attempt " + attempt + ")");
                    Thread.sleep(700L);
                    continue;
                }
                android.util.Log.i(TAG, "tap plus @" + plus + " (attempt " + attempt + ")");
                tapPoint(view, plus);
                Thread.sleep(900L);
                menuOpen = "1".equals(jsString(view, MENU_CHECK_SCRIPT));
                android.util.Log.i(TAG, "menu open=" + menuOpen + " after attempt " + attempt);
            }
            if (!menuOpen) {
                android.util.Log.w(TAG, "menu never opened");
                return jsFallback(view, attachments);
            }

            // 3) 触摸"相册"（实时读坐标）
            String pt = jsString(view, ALBUM_SCRIPT);
            if (pt == null || pt.isEmpty()) {
                android.util.Log.w(TAG, "album item not found");
                return jsFallback(view, attachments);
            }
            android.util.Log.i(TAG, "tap album @" + pt);
            tapPoint(view, pt);

            // 4) 等附件 chip 就绪（真实注入信号；最多 30s）
            for (int i = 0; i < 60; i++) {
                Thread.sleep(500L);
                if ("1".equals(jsString(view, CHIP_READY_SCRIPT))) {
                    android.util.Log.i(TAG, "chip ready at i=" + i);
                    Thread.sleep(1500L);
                    return true;
                }
                if (i % 8 == 0) android.util.Log.i(TAG, "waiting chip i=" + i);
            }
            android.util.Log.w(TAG, "chip timeout → js fallback");
            return jsFallback(view, attachments);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "touchInject error: " + t);
            return false;
        }
    }

    /** JS 兜底注入（与 chat 的 uploadAttachments 同款：DataTransfer 注入 + 等 chip）。 */
    private static boolean jsFallback(WebView view, List attachments) {
        try {
            String script = buildInjectScript(attachments);
            if (script == null) return false;
            jsString(view, script);
            android.util.Log.i(TAG, "js fallback injected");
            // 等 chip（最多 30s）
            for (int i = 0; i < 60; i++) {
                Thread.sleep(500L);
                if ("1".equals(jsString(view, CHIP_READY_SCRIPT))) {
                    android.util.Log.i(TAG, "js fallback: chip ready at i=" + i);
                    Thread.sleep(1500L);
                    return true;
                }
            }
            android.util.Log.w(TAG, "js fallback: chip timeout");
            return false;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "jsFallback error: " + t);
            return false;
        }
    }

    /** 构造 DataTransfer 注入脚本（name/mime/data 从 ChatAttachment 反射读取）。 */
    private static String buildInjectScript(List attachments) {
        try {
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < attachments.size(); i++) {
                Object a = attachments.get(i);
                String name = String.valueOf(a.getClass().getMethod("getName").invoke(a));
                String mime = String.valueOf(a.getClass().getMethod("getMime").invoke(a));
                String data = String.valueOf(a.getClass().getMethod("getDataUrl").invoke(a));
                if (i > 0) arr.append(",");
                arr.append("{\"name\":").append(org.json.JSONObject.quote(name))
                   .append(",\"mime\":").append(org.json.JSONObject.quote(mime))
                   .append(",\"data\":").append(org.json.JSONObject.quote(data)).append("}");
            }
            arr.append("]");
            return "(function(){try{var items=" + arr + ";"
                    + "var input=document.querySelector('input[type=\"file\"]');"
                    + "if(!input)return 'no_input';"
                    + "var dt=new DataTransfer();"
                    + "for(var k=0;k<items.length;k++){var item=items[k];"
                    + "var comma=item.data.indexOf(',');var bytes=atob(item.data.slice(comma+1));"
                    + "var data=new Uint8Array(bytes.length);"
                    + "for(var j=0;j<bytes.length;j++)data[j]=bytes.charCodeAt(j);"
                    + "dt.items.add(new File([data],item.name,{type:item.mime}));}"
                    + "input.files=dt.files;"
                    + "input.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "input.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "return 'ok';}catch(e){return String(e)}})()";
        } catch (Throwable t) {
            android.util.Log.w(TAG, "buildInjectScript error: " + t);
            return null;
        }
    }

    // ================= 心跳发送 =================

    private static volatile boolean autoSubmitArmed = false;

    private static void startAutoSubmit(final WebView view) {
        if (autoSubmitArmed) {
            android.util.Log.i(TAG, "auto-submit already armed, skip");
            return;
        }
        autoSubmitArmed = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 阶段0：等附件 chip 就绪（最多 90s）
                    boolean chip = false;
                    for (int i = 0; i < 180; i++) {
                        Thread.sleep(500L);
                        if ("1".equals(jsString(view, CHIP_READY_SCRIPT))) {
                            chip = true;
                            android.util.Log.i(TAG, "auto-submit: chip ready at i=" + i);
                            break;
                        }
                        if (i % 20 == 0) android.util.Log.i(TAG, "auto-submit: waiting chip i=" + i);
                    }
                    if (!chip) {
                        android.util.Log.w(TAG, "auto-submit: chip never ready, abort");
                        return;
                    }
                    // 阶段1：等输入框有文字（最多 60s）
                    boolean filled = false;
                    for (int i = 0; i < 120; i++) {
                        Thread.sleep(500L);
                        if ("1".equals(jsString(view, INPUT_FILLED_SCRIPT))) {
                            filled = true;
                            break;
                        }
                        if (i % 20 == 0) android.util.Log.i(TAG, "auto-submit: waiting input i=" + i);
                    }
                    if (!filled) {
                        android.util.Log.w(TAG, "auto-submit: input never filled, abort");
                        return;
                    }
                    android.util.Log.i(TAG, "auto-submit: input filled, start heartbeat");
                    Thread.sleep(800L);

                    // 阶段2：心跳触摸发送（12 拍）
                    for (int beat = 0; beat < 12; beat++) {
                        String center = jsString(view, SEND_CENTER_SCRIPT);
                        if (center == null || center.isEmpty()) {
                            android.util.Log.i(TAG, "auto-submit: send btn not found, beat " + beat);
                            Thread.sleep(1000L);
                            continue;
                        }
                        android.util.Log.i(TAG, "auto-submit: beat " + beat + " tap send @" + center);
                        tapPoint(view, center);
                        Thread.sleep(1300L);
                        if ("1".equals(jsString(view, INPUT_CLEARED_SCRIPT))) {
                            android.util.Log.i(TAG, "auto-submit: SENT! input cleared at beat " + beat);
                            return;
                        }
                    }
                    android.util.Log.w(TAG, "auto-submit: heartbeat timeout");
                } catch (Throwable e) {
                    android.util.Log.e(TAG, "auto-submit error: " + e);
                } finally {
                    autoSubmitArmed = false;
                }
            }
        }, "wenxin-auto-submit");
        t.setDaemon(true);
        t.start();
    }

    // ================= 脚本常量 =================

    private static final String PLUS_SCRIPT =
            "(function(){try{var b=document.querySelector('.cs-input-plus-btn');if(!b)return '';"
            + "var r=b.getBoundingClientRect();"
            + "if(r.width<=0||r.height<=0)return '';"
            + "return (Math.round(r.x+r.width/2))+','+(Math.round(r.y+r.height/2));}catch(e){return ''}})()";

    private static final String MENU_CHECK_SCRIPT =
            "(function(){try{var items=document.querySelectorAll('.chat-input-function-item');"
            + "for(var k=0;k<items.length;k++){if(items[k].offsetParent!==null)return '1';}"
            + "return '0';}catch(e){return '0'}})()";

    private static final String ALBUM_SCRIPT =
            "(function(){try{var items=document.querySelectorAll('.chat-input-function-item');"
            + "for(var k=0;k<items.length;k++){var it=items[k];if(it.offsetParent===null)continue;"
            + "if((it.textContent||'').indexOf('\\u76f8\\u518c')<0)continue;"
            + "var ic=it.querySelector('.chat-input-function-icon,.chat-input-function-image')||it;"
            + "var r=ic.getBoundingClientRect();"
            + "return (Math.round(r.x+r.width/2))+','+(Math.round(r.y+r.height/2));}"
            + "return '';}catch(e){return ''}})()";

    private static final String SEND_CENTER_SCRIPT =
            "(function(){try{"
            + "var cand=[];"
            + "var s1=document.querySelector('.cs-input-ds-send-btn');if(s1&&s1.offsetParent!==null)cand.push(s1);"
            + "if(!cand.length){var all=document.querySelectorAll('[class*=\"send\" i],button,[role=\"button\"]');"
            + "for(var k=0;k<all.length;k++){var e=all[k];if(e.offsetParent===null)continue;"
            + "var r=e.getBoundingClientRect();if(r.width<20||r.height<20)continue;"
            + "var h=((e.className||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.innerText||'')).toLowerCase();"
            + "if(/send|发送/.test(h))cand.push(e);}}"
            + "if(!cand.length)return '';"
            + "var t=cand[cand.length-1];var r=t.getBoundingClientRect();"
            + "return (Math.round(r.x+r.width/2))+','+(Math.round(r.y+r.height/2));"
            + "}catch(e){return ''}})()";

    private static final String CHIP_READY_SCRIPT =
            "(function(){try{var els=document.querySelectorAll('.cs-input-file-container,[class*=\"file-image-swiper\"]');"
            + "for(var k=0;k<els.length;k++){if(els[k].offsetParent!==null)return '1';}"
            + "return '0';}catch(e){return '0'}})()";

    private static final String INPUT_FILLED_SCRIPT =
            "(function(){try{var t=document.querySelector('textarea');"
            + "if(!t)return '0';"
            + "return ((t.value||'').trim()!=='')?'1':'0';}catch(e){return '0'}})()";

    private static final String INPUT_CLEARED_SCRIPT =
            "(function(){try{var t=document.querySelector('textarea');"
            + "if(!t)return '1';"
            + "return ((t.value||'').trim()==='')?'1':'0';}catch(e){return '0'}})()";

    /** 在主线程用真实 MotionEvent（DOWN→UP）点击 view 内 CSS 坐标点（局部坐标 = CSS×density）。 */
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

    /** 主线程 evaluateJavascript + 同步等待结果（去掉 JSON 引号包裹）。 */
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