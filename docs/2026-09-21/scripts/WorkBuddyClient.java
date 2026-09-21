package com.wenwei.deepseekproxy.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WorkBuddy 自动登录/换 token 机器（2026-09-15）。
 *
 * 【用户感知流程】
 *   1. 浏览器 tab 打开 workbuddy.cn 微信扫码登录一次（cookie 进进程级 CookieManager）
 *   2. 之后全自动：任何网关请求触发 ensureTokenAsync()——
 *        token 有效 → 什么都不做
 *        token 将过期 → refreshToken 静默刷新
 *        无 token 但有登录 cookie → 不可见 WebView 自动跑完
 *          auth/state → authUrl（同域登录页，已登录态显示「选择账号」）→ JS 自动点账号
 *          → 轮询 auth/token → 写回 prefs + 内存注册表
 *
 * 【调用方式（smali 注入点）】
 *   在 ProxyServer 路由入口（start$lambda$1$2 的 /v1/chat/completions 注册处）
 *   或 handleChatCompletions 方法入口插一行：
 *     invoke-static {}, Lcom/wenwei/deepseekproxy/proxy/WorkBuddyClient;->ensureTokenAsync()V
 *   零寄存器、非阻塞（内部起后台线程），不碰协程状态机。
 */
public final class WorkBuddyClient {

    public static final String BASE_CN = "https://www.workbuddy.cn";
    public static final String BASE_INTL = "https://www.codebuddy.ai";
    private static final String CLI_UA = "codebuddy-code/2.150.0 (CLI)";
    private static final String PREFS = "api_provider_configs";
    private static final String KEY = "providers_v1";
    /** 提前 1 天视为将过期 */
    private static final long SKEW_MS = 24L * 3600 * 1000;

    private static volatile boolean running = false;

    private WorkBuddyClient() {}

    // ================= 对外入口 =================

    /** 非阻塞：后台确保 WorkBuddy token 有效（无效则自动刷新/自动登录） */
    public static void ensureTokenAsync() {
        ensureTokenAsync(null);
    }

    public static void ensureTokenAsync(final Context ctx) {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = ctx != null ? ctx : appContext();
                    if (c == null) return;
                    ensureToken(c);
                } catch (Throwable ignored) {
                } finally {
                    running = false;
                }
            }
        }, "wb-token");
        t.setDaemon(true);
        t.start();
    }

    /** 同步版（测试/调试用） */
    public static void ensureToken(Context ctx) throws Exception {
        String[] saved = loadSavedToken(ctx, BASE_CN);
        String token = saved[0], refresh = saved[1];
        if (!token.isEmpty() && tokenValid(token)) return;              // 有效
        if (!refresh.isEmpty()) {                                        // 静默刷新
            try {
                String[] r = refreshToken(BASE_CN, refresh);
                persistToken(ctx, BASE_CN, r[0], r[1]);
                return;
            } catch (Throwable ignored) { }
        }
        if (cookieOf(BASE_CN).contains("qcloud") || cookieOf(BASE_CN).length() > 50) {
            runDance(ctx, BASE_CN);                                      // 有登录态 → 自动 dance
        }
    }

    // ================= 自动 dance（不可见 WebView） =================

    /**
     * 完整自动登录舞步：
     *   auth/state（cookie）→ 不可见 WebView 打开 authUrl → JS 自动点账号 → 轮询 auth/token → 写回
     */
    public static boolean runDance(Context ctx, String base) {
        try {
            final String[] st = authState(base);
            final String state = st[0], authUrl = st[1];
            if (state.isEmpty() || authUrl.isEmpty()) return false;

            final WebView[] wv = new WebView[1];
            final boolean[] clicked = {false};
            final CountDownLatch done = new CountDownLatch(1);
            final Handler main = new Handler(Looper.getMainLooper());

            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        WebView v = new WebView(appContext());
                        v.getSettings().setJavaScriptEnabled(true);
                        v.setWebViewClient(new WebViewClient() {
                            @Override
                            public void onPageFinished(WebView view, String url) {
                                // 页面渲染后自动点「选择账号」里的账号项（已登录态才显示）
                                view.evaluateJavascript(ACCOUNT_CLICK_JS, null);
                            }
                        });
                        wv[0] = v;
                        v.loadUrl(authUrl);
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                }
            });
            done.await(5, TimeUnit.SECONDS);

            // 给页面一点时间点击 + 服务端绑定
            Thread.sleep(2500);
            // 再补一次点击（SPA 可能晚渲染）
            if (wv[0] != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try { wv[0].evaluateJavascript(ACCOUNT_CLICK_JS, null); } catch (Throwable ignored) {}
                    }
                });
                Thread.sleep(1500);
            }

            // 轮询 token（最多 20s）
            for (int i = 0; i < 10; i++) {
                try {
                    String[] tok = authToken(base, state);
                    persistToken(ctx, base, tok[0], tok[1]);
                    return true;
                } catch (Throwable ignored) {
                    Thread.sleep(2000);
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 自动点账号的注入 JS：找列表里第一个可点项点击（已登录态的「选择账号」页） */
    private static final String ACCOUNT_CLICK_JS =
            "(function(){try{" +
            "var els=document.querySelectorAll('li,[role=button],[role=listitem],button,div');" +
            "for(var k=0;k<els.length;k++){var e=els[k];if(e.offsetParent===null)continue;" +
            "var t=(e.innerText||'').trim();if(t.length>1&&t.length<40&&!/选择|切换|登录|服务协议|隐私/.test(t)){" +
            "e.click();return 'clicked:'+t.slice(0,10);}}" +
            "return 'none';}catch(e){return String(e)}})()";

    // ================= token 存取（prefs + 内存注册表） =================

    /** 从 providers_v1 读 WorkBuddy 供应商的 token/refreshToken（返回 [token, refresh]） */
    public static String[] loadSavedToken(Context ctx, String base) {
        String host = hostOf(base);
        // 1) 优先读独立 prefs（App 的 persist 会丢掉未知字段，不能存 providers_v1）
        try {
            SharedPreferences sp = ctx.getSharedPreferences("workbuddy_tokens", Context.MODE_PRIVATE);
            String t = sp.getString(host + "_token", "");
            String r = sp.getString(host + "_refresh", "");
            if (!t.isEmpty()) return new String[]{t, r};
        } catch (Throwable ignored) { }
        // 2) 兜底：providers_v1 的 apiKey（明文）
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                String b = p.optString("baseUrl", "");
                if (b.contains(host)) {
                    String key = p.optString("apiKey", "");
                    if (key.startsWith("enc_v1:")) return new String[]{"", ""};
                    return new String[]{key, ""};
                }
            }
        } catch (Throwable ignored) { }
        return new String[]{"", ""};
    }

    /** 写回：providers_v1 的 apiKey（网关配置用）+ 独立 prefs（token/refreshToken 持久化） */
    public static void persistToken(Context ctx, String base, String token, String refresh) {
        try {
            String host = hostOf(base);
            // 1) 独立 prefs（App 不会碰，refreshToken 不会丢）
            SharedPreferences sp2 = ctx.getSharedPreferences("workbuddy_tokens", Context.MODE_PRIVATE);
            sp2.edit().putString(host + "_token", token)
                     .putString(host + "_refresh", refresh == null ? "" : refresh).apply();
            // 2) providers_v1 的 apiKey（明文：decrypt() legacy 通道原样返回）
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                if (p.optString("baseUrl", "").contains(host)) {
                    p.put("apiKey", token);
                    found = true;
                    break;
                }
            }
            if (found) sp.edit().putString(KEY, arr.toString()).apply();
            // 3) 刷新内存注册表（网关用的是 ApiProviderRegistry 的内存态）
            try {
                ApiProviderStore store = new ApiProviderStore(ctx);
                List configs = store.load();
                ApiProviderRegistry.set(configs);
            } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
    }

    // ================= 原有协议方法（authState/authToken/refresh/chat） =================

    public static String[] authState(String base) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(base + "/v2/plugin/auth/state?platform=CLI").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("X-CodeBuddy-Request", "1");
        conn.setRequestProperty("X-No-Authorization", "1");
        conn.setRequestProperty("User-Agent", CLI_UA);
        conn.setRequestProperty("Cookie", cookieOf(base));
        java.io.OutputStream os = conn.getOutputStream();
        os.write("{}".getBytes(StandardCharsets.UTF_8));
        os.close();
        String body = readAll(conn);
        JSONObject j = new JSONObject(body);
        JSONObject d = j.optJSONObject("data");
        if (d == null || d.optString("state", "").isEmpty()) {
            throw new RuntimeException("auth/state 未返回 state: " + body);
        }
        return new String[]{d.getString("state"), d.optString("authUrl", "")};
    }

    public static String[] authToken(String base, String state) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(base + "/v2/plugin/auth/token?state=" + state).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("X-CodeBuddy-Request", "1");
        conn.setRequestProperty("X-No-Authorization", "1");
        conn.setRequestProperty("User-Agent", CLI_UA);
        conn.setRequestProperty("Cookie", cookieOf(base));
        String body = readAll(conn);
        JSONObject j = new JSONObject(body);
        JSONObject d = j.optJSONObject("data");
        if (d == null || d.optString("accessToken", "").isEmpty()) {
            throw new RuntimeException("auth/token 未返回 accessToken: " + body);
        }
        String token = d.getString("accessToken");
        return new String[]{token, d.optString("refreshToken", ""), jwtSub(token)};
    }

    public static String[] refreshToken(String base, String refreshToken) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(base + "/v2/plugin/auth/token/refresh").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("X-CodeBuddy-Request", "1");
        // 实测：refreshToken 走 X-Refresh-Token 头（放 body 会被拒 10001 refreshToken is empty）
        conn.setRequestProperty("X-Refresh-Token", refreshToken);
        conn.setRequestProperty("X-Auth-Refresh-Source", "plugin");
        conn.setRequestProperty("User-Agent", CLI_UA);
        java.io.OutputStream os = conn.getOutputStream();
        os.write("{}".getBytes(StandardCharsets.UTF_8));
        os.close();
        String resp = readAll(conn);
        JSONObject j = new JSONObject(resp);
        JSONObject d = j.optJSONObject("data");
        if (d == null || d.optString("accessToken", "").isEmpty()) {
            throw new RuntimeException("token 刷新失败: " + resp);
        }
        String token = d.getString("accessToken");
        return new String[]{token, d.optString("refreshToken", refreshToken), jwtSub(token)};
    }

    public static void chat(String base, String accessToken, String uid,
                            String requestJson, LineSink sink) throws Exception {
        JSONObject req = new JSONObject(requestJson);
        req.put("stream", true);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(base + "/v2/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(600000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("X-CodeBuddy-Request", "1");
        conn.setRequestProperty("User-Agent", CLI_UA);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("X-User-Id", uid);
        conn.setRequestProperty("X-Domain", hostOf(base));
        java.io.OutputStream os = conn.getOutputStream();
        os.write(req.toString().getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = conn.getResponseCode();
        java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new RuntimeException("HTTP " + code + " 无响应体");
        if (code >= 400) throw new RuntimeException("HTTP " + code + ": " + readAll(is));
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) continue;
            sink.onLine(line);
        }
        br.close();
    }

    public interface LineSink { void onLine(String line); }

    // ================= 工具 =================

    /** JWT exp 是否还有效（提前 1 天算过期） */
    public static boolean tokenValid(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return false;
            String p = parts[1];
            p += "==".substring(0, (4 - p.length() % 4) % 4);
            byte[] dec = Base64.decode(p, Base64.URL_SAFE);
            JSONObject j = new JSONObject(new String(dec, StandardCharsets.UTF_8));
            long exp = j.optLong("exp", 0) * 1000L;
            return exp > System.currentTimeMillis() + SKEW_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String cookieOf(String base) {
        try {
            CookieManager cm = CookieManager.getInstance();
            String jar = cm.getCookie(base);
            return jar == null ? "" : jar;
        } catch (Exception e) {
            return "";
        }
    }

    public static String hostOf(String base) {
        try {
            return new java.net.URL(base).getHost();
        } catch (Exception e) {
            return base;
        }
    }

    public static String jwtSub(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return "";
            String p = parts[1];
            p += "==".substring(0, (4 - p.length() % 4) % 4);
            byte[] dec = Base64.decode(p, Base64.URL_SAFE);
            JSONObject j = new JSONObject(new String(dec, StandardCharsets.UTF_8));
            return j.optString("sub", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static Context appContext() {
        try {
            Class<?> c = Class.forName("com.wenwei.deepseekproxy.MainActivity");
            java.lang.reflect.Field f = c.getDeclaredField("instance");
            Object app = f.get(null);
            if (app != null) {
                java.lang.reflect.Method m = app.getClass().getMethod("getApplicationContext");
                return (Context) m.invoke(app);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String readAll(java.net.HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        return readAll(is);
    }

    private static String readAll(java.io.InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        is.close();
        return sb.toString();
    }
}
