package com.wenwei.deepseekproxy.proxy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 豆包视频生成 V2（2026-09-12 抓包对齐网页端新协议，纯 HttpURLConnection）。
 * 链路：
 *  1) POST /chat/completion（chat_ability.ability_type=17 + seedance_v2.0_mini）
 *     第一次 need_create_conversation=true 建会话，第二次同会话重发触发实际生成；
 *     注意：completion 的头必须与 PC 验证过的组合一致（桌面 UA、Content-Type 不带
 *     encoding 后缀、不发 Agw-Js-Conv），否则上游判"登录已过期"；
 *  2) 轮询 POST /im/chain/single（cmd=3100，必须带 Agw-Js-Conv: str +
 *     Content-Type: application/json; encoding=utf-8，否则 712012002 不支持编码类型）拿 vid；
 *  3) POST /creativity/resource/get_without_watermark {"vid":[vid]} 换无水印 mp4 直链。
 */
public final class DoubaoVideoV2 {
    private static final String BASE = "https://www.doubao.com";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; 25060RK16C Build/UQ1A.240205) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.7680.119 Mobile Safari/537.36";
    private static final String BOT_ID = "7338286299411103781";
    private static final String DEVICE_ID = "714003710229497";
    private static final String WEB_ID = "7604137868021548590";
    private static final String FP = "verify_mlcfw5f7_TPq0YmFD_NrsC_4RuQ_BJPg_M5W7i58I7wV0";
    private static final Pattern VID_RE = Pattern.compile("v0[0-9a-z]{20,}");
    private static final Pattern CID_RE = Pattern.compile("\"conversation_id\":\"(\\d{15,})\"");
    private static final Pattern SID_RE = Pattern.compile("\"section_id\":\"(\\d{15,})\"");

    private DoubaoVideoV2() {}

    /** 生成视频；返回 com.wenwei.deepseekproxy.proxy.DoubaoClient$DoubaoVideoResult（反射构造，失败时 urls 为空）。 */
    public static Object generate(Map cookies, String prompt) {
        StringBuilder cookieSb = new StringBuilder();
        for (Object o : ((Map) cookies).entrySet()) {
            Map.Entry e = (Map.Entry) o;
            if (cookieSb.length() > 0) cookieSb.append("; ");
            cookieSb.append(e.getKey()).append('=').append(e.getValue());
        }
        String cookieHeader = cookieSb.toString();
        String raw = "";
        try {
            String q = queryParams();
            // ---- completion #1：建会话（桌面 UA，无 Agw-Js-Conv）----
            JSONObject env1 = buildEnvelope(prompt, "", "", null, true);
            String sse1 = httpPost("/chat/completion?" + q, env1.toString(), cookieHeader, DESKTOP_UA,
                    "application/json", "text/event-stream", false, 600000);
            raw = sse1;
            String cid = first(sse1, CID_RE);
            String section = first(sse1, SID_RE);
            if (cid == null) throw new RuntimeException("completion#1 未返回 conversation_id");
            // ---- completion #2：同会话重发触发生成 ----
            JSONObject env2 = buildEnvelope(prompt, cid, section, Integer.valueOf(1), false);
            String sse2 = httpPost("/chat/completion?" + q, env2.toString(), cookieHeader, DESKTOP_UA,
                    "application/json", "text/event-stream", false, 600000);
            raw = sse1 + "\n====\n" + sse2;
            // ---- 轮询 im/chain/single 拿 vid（必须 Agw-Js-Conv + encoding=utf-8，移动 UA）----
            String vid = null;
            for (int attempt = 0; attempt < 24 && vid == null; attempt++) {
                Thread.sleep(10000L);
                JSONObject pull = new JSONObject();
                pull.put("cmd", 3100);
                JSONObject up = new JSONObject();
                JSONObject pullBody = new JSONObject();
                pullBody.put("conversation_id", cid);
                pullBody.put("anchor_index", 9007199254740991L);
                pullBody.put("conversation_type", 3);
                pullBody.put("direction", 1);
                pullBody.put("limit", 20);
                pullBody.put("ext", new JSONObject());
                pullBody.put("filter", new JSONObject().put("index_list", new JSONArray()));
                pullBody.put("evaluate_ab_params", "");
                pullBody.put("evaluate_common_params", "");
                up.put("pull_singe_chain_uplink_body", pullBody);
                pull.put("uplink_body", up);
                pull.put("sequence_id", uuid());
                pull.put("channel", 2);
                pull.put("version", "1");
                String resp = httpPost("/im/chain/single?" + q, pull.toString(), cookieHeader, MOBILE_UA,
                        "application/json; encoding=utf-8", "application/json, text/plain, */*", true, 120000);
                Matcher m = VID_RE.matcher(resp);
                if (m.find()) vid = m.group();
            }
            if (vid == null) throw new RuntimeException("轮询超时未拿到视频 vid");
            // ---- get_without_watermark 换无水印直链 ----
            JSONArray vidArr = new JSONArray();
            vidArr.put(vid);
            JSONObject wmBody = new JSONObject().put("vid", vidArr);
            String wmResp = httpPost("/creativity/resource/get_without_watermark?" + q, wmBody.toString(),
                    cookieHeader, MOBILE_UA, "application/json", "application/json, text/plain, */*", true, 120000);
            JSONObject wm = new JSONObject(wmResp);
            JSONObject downloadVideo = wm.getJSONObject("data").getJSONObject("download_video");
            List<String> urls = new ArrayList<>();
            for (java.util.Iterator<String> it = downloadVideo.keys(); it.hasNext();) {
                urls.add(downloadVideo.getJSONObject(it.next()).getString("download_url"));
            }
            if (urls.isEmpty()) throw new RuntimeException("download_video 为空");
            return buildResult(urls, prompt, raw.substring(Math.max(0, raw.length() - 6000)));
        } catch (Exception e) {
            List<String> empty = new ArrayList<>();
            return buildResult(empty, prompt, raw.isEmpty() ? ("error: " + e) : (raw + "\nerror: " + e));
        }
    }

    private static Object buildResult(List urls, String prompt, String raw) {
        try {
            Class<?> cls = Class.forName("com.wenwei.deepseekproxy.proxy.DoubaoClient$DoubaoVideoResult");
            return cls.getConstructor(java.util.List.class, String.class, String.class)
                    .newInstance(urls, prompt, raw);
        } catch (Exception e) {
            throw new RuntimeException("DoubaoVideoResult 反射构造失败", e);
        }
    }

    private static JSONObject buildEnvelope(String prompt, String conversationId, String sectionId,
                                            Integer lastIndex, boolean create) throws org.json.JSONException {
        String text = "生成视频：" + prompt + "，4s";
        JSONObject textBlock = new JSONObject()
                .put("text", text).put("icon_url", "").put("icon_url_dark", "").put("summary", "");
        JSONObject content = new JSONObject().put("text_block", textBlock).put("pc_event_block", "");
        JSONObject block = new JSONObject()
                .put("block_type", 10000)
                .put("content", content)
                .put("block_id", uuid())
                .put("parent_id", "")
                .put("meta_info", new JSONArray())
                .put("append_fields", new JSONArray());
        JSONObject message = new JSONObject()
                .put("local_message_id", uuid())
                .put("content_block", new JSONArray().put(block))
                .put("message_status", 0);

        JSONObject clientMeta = new JSONObject();
        if (create) {
            clientMeta.put("local_conversation_id", "local_" + uuid().replace("-", "").substring(0, 20));
        }
        clientMeta.put("conversation_id", conversationId == null ? "" : conversationId);
        clientMeta.put("bot_id", BOT_ID);
        clientMeta.put("last_section_id", sectionId == null ? "" : sectionId);
        if (lastIndex == null) {
            clientMeta.put("last_message_index", JSONObject.NULL);
        } else {
            clientMeta.put("last_message_index", lastIndex.intValue());
        }
        JSONArray perms = new JSONArray()
                .put(new JSONObject().put("permission_name", "ACCESS_COARSE_LOCATION").put("status", 3))
                .put(new JSONObject().put("permission_name", "ACCESS_FINE_LOCATION").put("status", 3))
                .put(new JSONObject().put("permission_name", "ACCESS_BACKGROUND_LOCATION").put("status", 3));
        clientMeta.put("local_permissions", perms);

        JSONObject abilityParam = new JSONObject()
                .put("ratio", "auto")
                .put("model", "seedance_v2.0_mini")
                .put("duration", 4)
                .put("input_box_content", new JSONObject()
                        .put("user_input_content", prompt)
                        .put("reply_message_format", "生成视频：%s"));
        JSONObject chatAbility = new JSONObject()
                .put("ability_type", 17)
                .put("ability_param", abilityParam.toString());

        JSONObject option = new JSONObject()
                .put("send_message_scene", "")
                .put("create_time_ms", System.currentTimeMillis())
                .put("collect_id", "")
                .put("is_audio", false)
                .put("answer_with_suggest", false)
                .put("tts_switch", false)
                .put("need_deep_think", 0)
                .put("click_clear_context", false)
                .put("from_suggest", false)
                .put("is_regen", false)
                .put("is_replace", false)
                .put("is_from_click_option", false)
                .put("is_from_click_softlink", false)
                .put("disable_sse_cache", false)
                .put("select_text_action", "")
                .put("is_select_text", false)
                .put("resend_for_regen", false)
                .put("scene_type", 0)
                .put("unique_key", uuid())
                .put("start_seq", 0)
                .put("need_create_conversation", create)
                .put("conversation_init_option", new JSONObject().put("need_ack_conversation", true))
                .put("regen_query_id", new JSONArray())
                .put("edit_query_id", new JSONArray())
                .put("regen_instruction", "")
                .put("no_replace_for_regen", false)
                .put("message_from", 0)
                .put("shared_app_name", "")
                .put("shared_app_id", "")
                .put("sse_recv_event_options", new JSONObject().put("support_chunk_delta", true))
                .put("support_lazy_fetch_stream", true)
                .put("is_ai_playground", false)
                .put("is_old_user", true)
                .put("recovery_option", new JSONObject()
                        .put("is_recovery", false)
                        .put("req_create_time_sec", System.currentTimeMillis() / 1000L)
                        .put("append_sse_event_scene", 0))
                .put("message_storage_type", 0)
                .put("related_deleted_message_ids", new JSONObject())
                .put("connector_info_list", new JSONArray())
                .put("model_config", new JSONObject().put("model_item_key", "").put("model_extra_params", new JSONObject()))
                .put("aggregate_params", new JSONObject()
                        .put("conversation_mode", create ? "1" : "")
                        .put("mode_id", "")
                        .put("model_item_key", "")
                        .put("agent_mode", "")
                        .put("reasoning_effort", "")
                        .put("provider_id", ""))
                .put("conversation_mode", create ? 1 : 0);
        if (!create) {
            option.put("general_task_param", new JSONObject()
                    .put("runtime_type", 0)
                    .put("agent_task_param", new JSONObject())
                    .put("agent_task_param_change", new JSONObject()
                            .put("runtime_changed", false)
                            .put("device_changed", false)
                            .put("sandbox_auth_type_changed", false))
                    .put("project_id", "")
                    .put("need_modify_conversation", false));
        }

        JSONObject ext = new JSONObject()
                .put("answer_with_suggest", "0")
                .put("sub_conv_firstmet_type", "1")
                .put("collection_id", "")
                .put("is_finish", "1")
                .put("conversation_init_option", "{\"need_ack_conversation\":true}")
                .put("commerce_credit_config_enable", "0");

        return new JSONObject()
                .put("client_meta", clientMeta)
                .put("messages", new JSONArray().put(message))
                .put("option", option)
                .put("chat_ability", chatAbility)
                .put("user_context", new JSONArray())
                .put("ext", ext);
    }

    private static String queryParams() {
        Map p = new LinkedHashMap();
        p.put("aid", "582478");
        p.put("real_aid", "582478");
        p.put("device_id", DEVICE_ID);
        p.put("tea_uuid", DEVICE_ID);
        p.put("web_id", WEB_ID);
        p.put("device_platform", "web");
        p.put("language", "zh");
        p.put("region", "CN");
        p.put("sys_region", "CN");
        p.put("pkg_type", "release_version");
        p.put("version_code", "20800");
        p.put("pc_version", "2.1.7");
        p.put("chromium_version", "135.0.7049.72");
        p.put("client_platform", "pc_client");
        p.put("runtime", "web");
        p.put("runtime_version", "3.5.4");
        p.put("samantha_web", "1");
        p.put("use-olympus-account", "1");
        p.put("fp", FP);
        p.put("web_tab_id", uuid());
        StringBuilder sb = new StringBuilder();
        for (Object o : p.entrySet()) {
            Map.Entry e = (Map.Entry) o;
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String httpPost(String pathWithQuery, String body, String cookieHeader, String ua,
                                   String contentType, String accept, boolean agwJsConv, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + pathWithQuery).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Origin", BASE);
        conn.setRequestProperty("Referer", BASE + "/chat");
        conn.setRequestProperty("Cookie", cookieHeader);
        if (agwJsConv) conn.setRequestProperty("Agw-Js-Conv", "str");
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

    private static String first(String s, Pattern p) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
