package com.wenwei.deepseekproxy.proxy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 美团（LongCat）视频生成 V2（2026-09-12 抓包对齐网页端协议，纯 HttpURLConnection）。
 * Cookie 直接取自 App 全局 WebView CookieManager（浏览器 tab 登录 longcat.chat 即可，无需保存会话）。
 * 链路：
 *  1) POST /api/v1/chat-completion-V2（agentId=genVideo）→ SSE 里拿 taskId（uuid）；
 *  2) 轮询 POST /api/v1/task-check {"taskList":[{type:1},{type:3,id:taskId}]} → SUCCESS 后
 *     extra[].content[].url 即视频直链（s3plus-bj02.sankuai.com，公开 CDN 无需 Cookie）。
 */
public final class LongCatVideoV2 {
    private static final String BASE = "https://longcat.chat";
    private static final String COOKIE_URL = "https://longcat.chat";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; 25060RK16C Build/UQ1A.240205) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.7680.119 Mobile Safari/537.36";
    private static final Pattern TASK_RE = Pattern.compile("\"taskId\":\"([0-9a-f\\-]{36})\"");

    private LongCatVideoV2() {}

    /** 生成视频，返回视频 URL 列表（失败抛异常，由路由转错误响应）。 */
    public static List<String> generate(String prompt) throws Exception {
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        String cookie = cm.getCookie(COOKIE_URL);
        if (cookie == null || cookie.isEmpty()) {
            throw new RuntimeException("LongCat Cookie 不存在：请先在浏览器 tab 打开 longcat.chat 并登录");
        }
        String q = "yodaReady=h5&csecplatform=4&csecversion=4.3.0";

        // 1) 发起生成
        JSONObject body = new JSONObject()
                .put("conversationId", "")
                .put("content", prompt)
                .put("agentId", "genVideo")
                .put("files", new JSONArray())
                .put("creationParam", new JSONObject()
                        .put("videoMode", "single")
                        .put("width", 16)
                        .put("height", 9)
                        .put("style", ""))
                .put("reasonEnabled", 0)
                .put("searchEnabled", 0)
                .put("location", new JSONArray());
        String resp = httpPost(BASE + "/api/v1/chat-completion-V2?" + q, body.toString(), cookie, 600000);
        Matcher m = TASK_RE.matcher(resp);
        if (!m.find()) {
            throw new RuntimeException("chat-completion-V2 未返回 taskId: "
                    + resp.substring(0, Math.min(300, resp.length())));
        }
        String taskId = m.group(1);

        // 2) 轮询任务状态（最长 10 分钟）
        long deadline = System.currentTimeMillis() + 600_000L;
        List<String> urls = new ArrayList<>();
        String lastStatus = "";
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(8000L);
            JSONObject tc = new JSONObject()
                    .put("taskList", new JSONArray()
                            .put(new JSONObject().put("type", 1).put("id", ""))
                            .put(new JSONObject().put("type", 3).put("id", taskId)));
            String tr = httpPost(BASE + "/api/v1/task-check?" + q, tc.toString(), cookie, 120000);
            JSONObject root = new JSONObject(tr);
            org.json.JSONArray results = root.getJSONObject("data").getJSONArray("taskResult");
            String videoUrl = "";
            String status = "";
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                if (!taskId.equals(r.optString("id"))) continue;
                status = r.optString("status");
                org.json.JSONArray extra = r.optJSONArray("extra");
                if (extra == null) continue;
                for (int j = 0; j < extra.length(); j++) {
                    JSONObject e = extra.optJSONObject(j);
                    if (e == null) continue;
                    org.json.JSONArray contents = e.optJSONArray("content");
                    if (contents == null) continue;
                    for (int k = 0; k < contents.length(); k++) {
                        JSONObject c = contents.optJSONObject(k);
                        if (c != null) {
                            String u = c.optString("url", "");
                            if (!u.isEmpty()) videoUrl = u;
                        }
                    }
                }
            }
            lastStatus = status;
            if (!videoUrl.isEmpty()) {
                urls.add(videoUrl);
                break;
            }
            if ("FAILED".equals(status) || "ERROR".equals(status)) {
                throw new RuntimeException("LongCat 任务失败: " + status);
            }
        }
        if (urls.isEmpty()) throw new RuntimeException("LongCat 轮询超时（10 分钟），最后状态: " + lastStatus);
        return urls;
    }

    private static String httpPost(String url, String body, String cookie, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Origin", BASE);
        conn.setRequestProperty("Referer", BASE + "/chat");
        conn.setRequestProperty("Cookie", cookie);
        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new RuntimeException("HTTP " + code + " 无响应体");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400) throw new RuntimeException("HTTP " + code + ": " + text.substring(0, Math.min(300, text.length())));
        return text;
    }
}
