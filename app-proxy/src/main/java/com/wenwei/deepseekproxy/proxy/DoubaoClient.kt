package com.wenwei.deepseekproxy.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 豆包（Doubao）接口客户端。认证完全依赖 Cookie（sessionid 等），
 * 无需 HMAC 签名。端口自 doubao2api.js（桌面客户端协议 / Samantha 生图协议）。
 *
 *  - 聊天：POST https://www.doubao.com/chat/completion （桌面客户端协议，SSE 流式）
 *  - 生图：POST https://www.doubao.com/samantha/chat/completion （Samantha 协议，content_type=2009）
 */
object DoubaoClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private const val BASE_URL = "https://www.doubao.com"
    private const val DEFAULT_BOT_ID = "7234781073513644036"
    private const val EXTENSION_BOT_ID = "7338286299411103781"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

    // 固定设备指纹（与 doubao2api.js 默认一致）
    private const val DEVICE_ID = "714003710229497"
    private const val WEB_ID = "7604137868021548590"
    private const val FP = "verify_mlcfw5f7_TPq0YmFD_NrsC_4RuQ_BJPg_M5W7i58I7wV0"

    data class DoubaoImage(val key: String, val oriUrl: String, val rawUrl: String, val thumbUrl: String, val width: Int, val height: Int, val format: String)
    data class DoubaoImageResult(val images: List<DoubaoImage>, val prompt: String)

    // ── Cookie 解析 ──────────────────────────────────────────
    fun parseCookie(raw: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        raw.split(';').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@forEach
            val k = pair.substring(0, idx).trim()
            val v = pair.substring(idx + 1).trim()
            if (k.isNotEmpty()) map[k] = v
        }
        return map
    }

    // ── Security Params ─────────────────────────────────────
    private fun securityParams(): Map<String, String> {
        val params = linkedMapOf(
            "aid" to "582478",
            "real_aid" to "582478",
            "device_id" to DEVICE_ID,
            "tea_uuid" to DEVICE_ID,
            "web_id" to WEB_ID,
            "device_platform" to "web",
            "language" to "zh",
            "region" to "CN",
            "sys_region" to "CN",
            "pkg_type" to "release_version",
            "version_code" to "20800",
            "pc_version" to "2.1.7",
            "chromium_version" to "135.0.7049.72",
            "client_platform" to "pc_client",
            "runtime" to "web",
            "runtime_version" to "3.5.4",
            "samantha_web" to "1",
            "use-olympus-account" to "1",
            "fp" to FP,
            "web_tab_id" to UUID.randomUUID().toString()
        )
        return params
    }

    private fun buildQueryString(params: Map<String, String>): String {
        return params.entries.sortedBy { it.key }.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private fun uuid() = UUID.randomUUID().toString()
    private fun uuid16() = uuid().replace("-", "").take(16)

    // ── 聊天 payload（桌面客户端协议）─────────────────────────
    private fun buildCompletionPayload(text: String, needDeepThink: Int, images: List<DoubaoUploadedImage>): String {
        val blocks = JSONArray()

        // 文本块
        blocks.put(JSONObject().apply {
            put("block_type", 10000)
            put("content", JSONObject().apply {
                put("text_block", JSONObject().apply {
                    put("text", text)
                    put("icon_url", "")
                    put("icon_url_dark", "")
                    put("summary", "")
                })
                put("pc_event_block", "")
            })
            put("block_id", uuid())
            put("parent_id", "")
            put("meta_info", JSONArray())
            put("append_fields", JSONArray())
            put("is_finish", true)
            put("patch_type", 2)
        })

        // 图片块（block_type=10052，attachment_block.attachments[]）
        if (images.isNotEmpty()) {
            val attachments = JSONArray()
            for (img in images) {
                attachments.put(JSONObject().apply {
                    put("type", 1)
                    put("identifier", uuid())
                    put("image", JSONObject().apply {
                        put("name", img.name)
                        put("uri", img.uri)
                        put("image_ori", JSONObject().apply {
                            put("url", img.url)
                            put("width", img.width)
                            put("height", img.height)
                            put("format", img.format)
                            put("url_formats", JSONObject())
                        })
                    })
                    put("parse_state", 0)
                    put("review_state", 0)
                })
            }
            blocks.put(JSONObject().apply {
                put("block_type", 10052)
                put("content", JSONObject().apply {
                    put("attachment_block", JSONObject().apply {
                        put("attachments", attachments)
                    })
                })
                put("block_id", uuid())
                put("parent_id", "")
                put("meta_info", JSONArray())
                put("append_fields", JSONArray())
                put("is_finish", true)
                put("patch_type", 2)
            })
        }

        val payload = JSONObject().apply {
            put("client_meta", JSONObject().apply {
                put("local_conversation_id", "local_${uuid16()}")
                put("conversation_id", "")
                put("bot_id", EXTENSION_BOT_ID)
                put("last_section_id", "")
                put("last_message_index", 0)
            })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("local_message_id", uuid())
                    put("content_block", blocks)
                    put("message_status", 0)
                })
            })
            put("option", JSONObject().apply {
                put("send_message_scene", "")
                put("create_time_ms", 0)
                put("collect_id", "")
                put("is_audio", false)
                put("answer_with_suggest", true)
                put("tts_switch", false)
                put("need_deep_think", needDeepThink)
                put("click_clear_context", false)
                put("from_suggest", false)
                put("is_regen", false)
                put("is_replace", false)
                put("disable_sse_cache", false)
                put("select_text_action", "")
                put("resend_for_regen", false)
                put("scene_type", 0)
                put("unique_key", uuid())
                put("start_seq", 0)
                put("need_create_conversation", true)
                put("conversation_init_option", JSONObject().apply {
                    put("need_ack_conversation", true)
                })
                put("regen_query_id", JSONArray())
                put("edit_query_id", JSONArray())
                put("regen_instruction", "")
                put("no_replace_for_regen", false)
                put("message_from", 0)
                put("shared_app_name", "")
                put("action_bar_skill_id", 0)
                put("sse_recv_event_options", JSONObject().apply { put("support_chunk_delta", true) })
                put("is_ai_playground", false)
            })
            put("chat_ability", JSONObject())
            put("ext", JSONObject().apply {
                put("use_deep_think", needDeepThink.toString())
                put("fp", FP)
                put("use_submit_pipeline", "1")
                put("commerce_credit_config_enable", "0")
                put("sub_conv_firstmet_type", "1")
            })
        }
        return payload.toString()
    }

    /** 已上传图片（upload_image → get_file_url 后的 uri/url + 尺寸信息）。 */
    data class DoubaoUploadedImage(val name: String, val uri: String, val url: String, val width: Int, val height: Int, val format: String)

    /**
     * 上传图片到豆包图床（旧网页协议，聊天发图同款）：
     *  1) POST /samantha/pages/upload_image（multipart data + file_type）→ data.uri
     *  2) POST /alice/message/get_file_url → ocean-cloud-tos/ 前缀 uri + main_url
     * 返回 DoubaoUploadedImage（uri=ocean-cloud-tos 格式，url=签名 main_url）。
     */
    fun uploadImage(bytes: ByteArray, ext: String, cookies: Map<String, String>, width: Int = 0, height: Int = 0): DoubaoUploadedImage {
        val params = securityParams()
        val url = "$BASE_URL/samantha/pages/upload_image?${buildQueryString(params)}"
        val csrfToken = cookies["passport_csrf_token"] ?: cookies["passport_csrf_token_default"] ?: ""
        val cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        val mime = when (ext.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("data", "image.$ext", bytes.toRequestBody(mime.toMediaType()))
            .addFormDataPart("file_type", ext)
            .build()
        val req = Request.Builder().url(url)
            .post(body)
            .header("x-tt-passport-csrf-token", csrfToken)
            .header("User-Agent", UA)
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/chat")
            .header("Cookie", cookieHeader)
            .build()
        val rawUri = client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("豆包图片上传 HTTP ${resp.code}: ${text.take(300)}")
            }
            val data = JSONObject(text).optJSONObject("data")
            val uri = data?.optString("uri") ?: ""
            if (uri.isBlank()) throw RuntimeException("豆包图片上传未返回 uri: ${text.take(200)}")
            uri
        }
        val (fileUri, mainUrl) = getFileUrl(rawUri, ext.lowercase(), cookies, csrfToken, cookieHeader)
        return DoubaoUploadedImage("image.$ext", fileUri, mainUrl, width, height, ext.lowercase())
    }

    /**
     * 把 upload_image 返回的原始 uri 转换为聊天可用的文件 uri + main_url。
     * POST /alice/message/get_file_url {"uris":[uri],"type":"image","format":<ext>,"expire_second":3600}
     * → data.file_urls[0].uri（带 ocean-cloud-tos/ 前缀）+ main_url（签名 URL）。
     */
    private fun getFileUrl(
        rawUri: String,
        format: String,
        cookies: Map<String, String>,
        csrfToken: String,
        cookieHeader: String,
    ): Pair<String, String> {
        val params = securityParams()
        val url = "$BASE_URL/alice/message/get_file_url?${buildQueryString(params)}"
        val payload = JSONObject().apply {
            put("uris", JSONArray().apply { put(rawUri) })
            put("type", "image")
            put("format", format)
            put("expire_second", 3600)
        }.toString()
        val req = Request.Builder().url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("x-tt-passport-csrf-token", csrfToken)
            .header("User-Agent", UA)
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/chat")
            .header("Cookie", cookieHeader)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("豆包 get_file_url HTTP ${resp.code}: ${text.take(300)}")
            }
            val fu = runCatching {
                val data = JSONObject(text).optJSONObject("data")
                data?.optJSONArray("file_urls")?.optJSONObject(0)
            }.getOrNull()
            val fileUri = fu?.optString("uri")?.takeIf { it.isNotBlank() } ?: rawUri
            val mainUrl = fu?.optString("main_url") ?: ""
            return fileUri to mainUrl
        }
    }

    // ── 生图参考图 ImageX 直传（JWT 由服务端 ApplyImageUpload 签发，无需 SpaceKey）──────────
    private const val IMAGEX_REGION = "cn-north-1"
    private const val IMAGEX_SERVICE = "imagex"
    private const val IMAGEX_TOP_URL = "$BASE_URL/top/v1"

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String =
        hmacSha256(key, data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** RFC3986 编码（AWS SigV4 canonical query 用）。 */
    private fun rfc3986(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    /**
     * 火山引擎 ImageX 的 AWS SigV4 签名（region=cn-north-1, service=imagex）。
     * 返回 Authorization 及附加请求头（X-Amz-Date / x-amz-security-token / X-Amz-Content-Sha256）。
     */
    private fun imagexSigV4(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String?,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String,
    ): Map<String, String> {
        val utc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val amzDate = utc.format(Date())
        val date = amzDate.substring(0, 8)
        val payloadHash = sha256Hex((body ?: "").toByteArray(Charsets.UTF_8))

        val canonicalQuery = query.toSortedMap().entries.joinToString("&") { "${rfc3986(it.key)}=${rfc3986(it.value)}" }
        val hasBody = body != null
        val signedHeaders = if (hasBody) "x-amz-content-sha256;x-amz-date;x-amz-security-token"
                            else "x-amz-date;x-amz-security-token"
        val canonicalHeaders = if (hasBody) {
            "x-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\nx-amz-security-token:$sessionToken\n"
        } else {
            "x-amz-date:$amzDate\nx-amz-security-token:$sessionToken\n"
        }
        val canonicalRequest = listOf(
            method, path, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash
        ).joinToString("\n")

        val credentialScope = "$date/$IMAGEX_REGION/$IMAGEX_SERVICE/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n" +
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))

        val kDate = hmacSha256("AWS4$secretAccessKey".toByteArray(Charsets.UTF_8), date.toByteArray(Charsets.UTF_8))
        val kRegion = hmacSha256(kDate, IMAGEX_REGION.toByteArray(Charsets.UTF_8))
        val kService = hmacSha256(kRegion, IMAGEX_SERVICE.toByteArray(Charsets.UTF_8))
        val kSigning = hmacSha256(kService, "aws4_request".toByteArray(Charsets.UTF_8))
        val signature = hmacSha256Hex(kSigning, stringToSign)

        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKeyId/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
        val headers = linkedMapOf(
            "Authorization" to authorization,
            "X-Amz-Date" to amzDate,
            "x-amz-security-token" to sessionToken,
        )
        if (hasBody) headers["X-Amz-Content-Sha256"] = payloadHash
        return headers
    }

    /**
     * 生图参考图 ImageX 直传：
     *  prepare_upload(拿 STS) → ApplyImageUpload(SigV4 拿 Auth) → 直传 TOS → CommitImageUpload(SigV4 拿 tos-cn-i- uri)。
     * 返回 DoubaoUploadedImage（uri=tos-cn-i- 格式，供新版 /chat/completion 参考图用）。
     */
    fun uploadImageImageX(bytes: ByteArray, ext: String, cookies: Map<String, String>): DoubaoUploadedImage {
        val cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        val csrfToken = cookies["passport_csrf_token"] ?: cookies["passport_csrf_token_default"] ?: ""

        // 1. prepare_upload 拿 STS（access_key / secret_key / session_token）
        val prepBody = JSONObject().apply {
            put("tenant_id", "5")
            put("scene_id", "5")
            put("resource_type", 2)
        }.toString()
        val prepUrl = "$BASE_URL/alice/resource/prepare_upload?${buildQueryString(securityParams())}"
        val prepReq = Request.Builder().url(prepUrl)
            .post(prepBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("x-tt-passport-csrf-token", csrfToken)
            .header("User-Agent", UA)
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/chat")
            .header("Cookie", cookieHeader)
            .build()
        val prepData = client.newCall(prepReq).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("豆包 prepare_upload HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text).optJSONObject("data")
                ?: throw RuntimeException("豆包 prepare_upload 无 data: ${text.take(300)}")
        }
        val serviceId = prepData.optString("service_id", "a9rns2rl98")
        val tok = prepData.optJSONObject("upload_auth_token")
            ?: throw RuntimeException("prepare_upload 无 upload_auth_token")
        val accessKey = tok.optString("access_key")
        val secretKey = tok.optString("secret_key")
        val sessionToken = tok.optString("session_token")
        if (accessKey.isBlank() || secretKey.isBlank() || sessionToken.isBlank()) {
            throw RuntimeException("prepare_upload STS 字段缺失")
        }

        // 2. ApplyImageUpload（SigV4，GET）→ Auth / SessionKey / StoreUri / UploadHosts
        val fileExt = if (ext.startsWith(".")) ext else ".$ext"
        val applyQuery = linkedMapOf(
            "Action" to "ApplyImageUpload",
            "Version" to "2018-08-01",
            "ServiceId" to serviceId,
            "NeedFallback" to "true",
            "FileSize" to bytes.size.toString(),
            "FileExtension" to fileExt,
            "s" to UUID.randomUUID().toString().replace("-", "").substring(0, 11),
        )
        val applyHeaders = imagexSigV4("GET", "/top/v1", applyQuery, null, accessKey, secretKey, sessionToken)
        val applyQ = applyQuery.entries.joinToString("&") { "${rfc3986(it.key)}=${rfc3986(it.value)}" }
        val applyReq = Request.Builder().url("$IMAGEX_TOP_URL?$applyQ")
            .get()
            .header("Authorization", applyHeaders["Authorization"]!!)
            .header("X-Amz-Date", applyHeaders["X-Amz-Date"]!!)
            .header("x-amz-security-token", applyHeaders["x-amz-security-token"]!!)
            .header("User-Agent", UA)
            .header("Referer", "$BASE_URL/chat/")
            .header("Cookie", cookieHeader)
            .build()
        val applyJson = client.newCall(applyReq).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("豆包 ApplyImageUpload HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text)
        }
        val uploadAddress = applyJson.optJSONObject("Result")?.optJSONObject("UploadAddress")
            ?: throw RuntimeException("ApplyImageUpload 无 UploadAddress: ${applyJson.toString().take(300)}")
        val storeInfo = uploadAddress.optJSONArray("StoreInfos")?.optJSONObject(0)
            ?: throw RuntimeException("ApplyImageUpload 无 StoreInfos[0]")
        val auth = storeInfo.optString("Auth")
        val storeUri = storeInfo.optString("StoreUri")
        val sessionKey = uploadAddress.optString("SessionKey")
        val uploadHost = uploadAddress.optJSONArray("UploadHosts")?.optString(0) ?: ""
        if (auth.isBlank() || storeUri.isBlank() || uploadHost.isBlank()) {
            throw RuntimeException("ApplyImageUpload 返回字段缺失")
        }

        // 3. 直传 TOS
        val crcHex = "%08x".format(CRC32().apply { update(bytes) }.value)
        val uploadReq = Request.Builder().url("https://$uploadHost/upload/v1/$storeUri")
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .header("Content-Type", "application/octet-stream")
            .header("Authorization", auth)
            .header("Content-CRC32", crcHex)
            .header("User-Agent", UA)
            .build()
        client.newCall(uploadReq).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("豆包直传 HTTP ${resp.code}: ${text.take(300)}")
        }

        // 4. CommitImageUpload（SigV4，POST，body=SessionKey）→ tos-cn-i- uri
        val commitBody = JSONObject().put("SessionKey", sessionKey).toString()
        val commitQuery = linkedMapOf(
            "Action" to "CommitImageUpload",
            "Version" to "2018-08-01",
            "ServiceId" to serviceId,
        )
        val commitHeaders = imagexSigV4("POST", "/top/v1", commitQuery, commitBody, accessKey, secretKey, sessionToken)
        val commitQ = commitQuery.entries.joinToString("&") { "${rfc3986(it.key)}=${rfc3986(it.value)}" }
        val commitReq = Request.Builder().url("$IMAGEX_TOP_URL?$commitQ")
            .post(commitBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Authorization", commitHeaders["Authorization"]!!)
            .header("X-Amz-Date", commitHeaders["X-Amz-Date"]!!)
            .header("x-amz-security-token", commitHeaders["x-amz-security-token"]!!)
            .header("X-Amz-Content-Sha256", commitHeaders["X-Amz-Content-Sha256"]!!)
            .header("User-Agent", UA)
            .header("Referer", "$BASE_URL/chat/")
            .header("Cookie", cookieHeader)
            .build()
        val commitJson = client.newCall(commitReq).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("豆包 CommitImageUpload HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text)
        }
        val result = commitJson.optJSONObject("Result")?.optJSONArray("Results")?.optJSONObject(0)
            ?: throw RuntimeException("CommitImageUpload 无 Results[0]: ${commitJson.toString().take(300)}")
        val uri = result.optString("Uri")
        if (uri.isBlank()) throw RuntimeException("CommitImageUpload 未返回 Uri")

        // 尺寸/格式从 Commit 返回的 PluginResult 取
        val plugin = commitJson.optJSONObject("Result")?.optJSONArray("PluginResult")?.optJSONObject(0)
        val width = plugin?.optInt("ImageWidth", 0) ?: 0
        val height = plugin?.optInt("ImageHeight", 0) ?: 0
        val format = plugin?.optString("ImageFormat", "") ?: ""

        return DoubaoUploadedImage("image.$ext", uri, "", width, height, format.ifBlank { ext.lowercase() })
    }

    /**
     * 生图会话相关：通过 create_section 建会话拿 conversation_id + section_id（无聊天记录），
     * 再 pre_handle_v2 绑定参考图 key（新版 /chat/completion 生图前置步骤）。
     */

    /** 建会话（/alice/conversation/create_section）。返回 Pair(conversationId, sectionId)。 */
    private fun createConversationSection(cookies: Map<String, String>): Pair<String, String> {
        val cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        val csrfToken = cookies["passport_csrf_token"] ?: cookies["passport_csrf_token_default"] ?: ""
        val body = JSONObject().apply {
            put("conversation_id", "")
            put("local_conversation_id", "local_${uuid16()}")
            put("bot_id", EXTENSION_BOT_ID)
            put("ext", JSONObject())
        }.toString()
        val url = "$BASE_URL/alice/conversation/create_section?${buildQueryString(securityParams())}"
        val req = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("x-tt-passport-csrf-token", csrfToken)
            .header("User-Agent", UA)
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/chat")
            .header("Cookie", cookieHeader)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("豆包建会话 HTTP ${resp.code}: ${text.take(300)}")
            val data = JSONObject(text).optJSONObject("data")
                ?: throw RuntimeException("豆包建会话无 data: ${text.take(300)}")
            val convId = data.optString("conversation_id")
            val sectionId = data.optString("section_id")
            if (convId.isBlank() || sectionId.isBlank()) throw RuntimeException("豆包建会话缺 conversation_id/section_id")
            return convId to sectionId
        }
    }

    /** pre_handle_v2：绑定参考图 key 到会话（identifier 与 attachment.identifier 一致）。 */
    private fun preHandleV2(
        uri: String,
        convId: String,
        sectionId: String,
        localMessageId: String,
        identifier: String,
        cookies: Map<String, String>,
    ) {
        val cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        val csrfToken = cookies["passport_csrf_token"] ?: cookies["passport_csrf_token_default"] ?: ""
        val body = JSONObject().apply {
            put("uplink_entity", JSONObject().apply {
                put("entity_type", 2)
                put("entity_content", JSONObject().apply {
                    put("image", JSONObject().apply { put("key", uri) })
                })
                put("identifier", identifier)
            })
            put("conversation_id", convId)
            put("bot_id", EXTENSION_BOT_ID)
            put("section_id", sectionId)
            put("local_message_id", localMessageId)
        }.toString()
        val url = "$BASE_URL/alice/message/pre_handle_v2?${buildQueryString(securityParams())}"
        val req = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("x-tt-passport-csrf-token", csrfToken)
            .header("User-Agent", UA)
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/chat")
            .header("Cookie", cookieHeader)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("豆包 pre_handle_v2 HTTP ${resp.code}: ${text.take(300)}")
            val code = JSONObject(text).optInt("code", -1)
            if (code != 0) throw RuntimeException("豆包 pre_handle_v2 code=$code: ${text.take(300)}")
        }
    }

    /**
     * 调用 /chat/completion（流式）。返回的 Response 由调用方读取并关闭。     * 若返回非 SSE（如 JSON 鉴权错误），会在内部读取并抛出异常。
     */
    fun chatCompletion(text: String, cookies: Map<String, String>, needDeepThink: Int = 0, images: List<DoubaoUploadedImage> = emptyList()): Response {
        val params = securityParams()
        val url = "$BASE_URL/chat/completion?${buildQueryString(params)}"
        val csrfToken = cookies["passport_csrf_token"] ?: cookies["passport_csrf_token_default"] ?: ""

        val cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }

        val req = Request.Builder().url(url)
            .post(buildCompletionPayload(text, needDeepThink, images).toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("x-tt-passport-csrf-token", csrfToken)
            .header("User-Agent", UA)
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/chat")
            .header("Cookie", cookieHeader)
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string() ?: ""
            resp.close()
            throw RuntimeException("豆包 /chat/completion HTTP ${resp.code}: ${err.take(300)}")
        }
        val ct = resp.header("Content-Type") ?: ""
        if (!ct.contains("text/event-stream", ignoreCase = true)) {
            val bodyText = resp.body?.string() ?: ""
            resp.close()
            try {
                val o = JSONObject(bodyText)
                if (o.has("code")) {
                    throw RuntimeException("豆包鉴权错误: code=${o.opt("code")} msg=${o.opt("msg") ?: o.opt("message") ?: ""}")
                }
            } catch (_: Exception) {
            }
            throw RuntimeException("豆包 /chat/completion 响应异常(content-type=$ct): ${bodyText.take(300)}")
        }
        return resp
    }

    // ── 生图 payload ─────────────────────────────────────
    /** 生图参考图（原始字节，上传前）。 */
    data class RefImage(val name: String, val bytes: ByteArray, val width: Int = 0, val height: Int = 0, val mime: String = "image/jpeg")

    /**
     * 纯文本生图（无参考图）：Samantha 协议（已验证可用）。
     */
    private fun buildImageGenPayload(prompt: String, ratio: String?): String {
        val contentObj = JSONObject().apply {
            put("text", prompt)
            if (!ratio.isNullOrBlank()) put("ratio", ratio)
        }
        val msg = JSONObject().apply {
            put("content", contentObj.toString())
            put("content_type", 2009)
            put("attachments", JSONArray())
            put("references", JSONArray())
            put("skill", JSONObject().apply {
                put("skill_type", 3)
                put("skill_type_no_default", 3)
                put("skill_id", "3")
                put("skill_id_no_default", "3")
            })
        }
        return JSONObject().apply {
            put("messages", JSONArray().apply { put(msg) })
            put("completion_option", JSONObject().apply {
                put("is_regen", false)
                put("with_suggest", true)
                put("need_create_conversation", true)
                put("launch_stage", 1)
                put("is_replace", false)
                put("is_delete", false)
                put("is_ai_playground", false)
                put("memory_type", 2)
                put("message_from", 0)
                put("use_deep_think", false)
                put("use_auto_cot", false)
                put("resend_for_regen", false)
                put("enable_commerce_credit", false)
                put("action_bar_skill_id", 3)
            })
            put("evaluate_option", JSONObject().apply { put("web_ab_params", "") })
            put("local_conversation_id", uuid())
            put("local_message_id", uuid())
        }.toString()
    }

    /**
     * 带参考图生图（图生图）：新版 /chat/completion 协议（网页端实测结构）。
     * 参考图用 block_type=10052 attachment_block，文本用 block_type=10000，
     * 两者各占一个 message；client_meta 必须带非空 conversation_id + last_section_id
     * （由 create_section + pre_handle_v2 前置获取/绑定），否则 710020202 common invalid param。
     * 关键字段：image_ori.url/format 都为空、upload_status:1、progress:100、review_state:1，
     * 以及 chat_ability（Seedream 4.5）。
     */
    private fun buildImageGenPayloadWithRefs(
        prompt: String,
        ratio: String?,
        refs: List<DoubaoUploadedImage>,
        convId: String,
        sectionId: String,
        localMessageId: String,
        identifier: String,
    ): String {
        // 参考图 message（block_type=10052）
        val attachments = JSONArray()
        for (ref in refs) {
            attachments.put(JSONObject().apply {
                put("type", 1)
                put("identifier", identifier)
                put("image", JSONObject().apply {
                    put("name", ref.name)
                    put("uri", ref.uri)
                    put("image_ori", JSONObject().apply {
                        put("url", "")
                        put("width", ref.width)
                        put("height", ref.height)
                        put("format", "")
                        put("url_formats", JSONObject())
                    })
                })
                put("parse_state", 0)
                put("review_state", 1)
                put("upload_status", 1)
                put("progress", 100)
                put("src", "")
            })
        }
        val imageBlock = JSONObject().apply {
            put("block_type", 10052)
            put("content", JSONObject().apply {
                put("attachment_block", JSONObject().apply {
                    put("attachments", attachments)
                })
                put("pc_event_block", "")
            })
            put("block_id", uuid())
            put("parent_id", "")
            put("meta_info", JSONArray())
            put("append_fields", JSONArray())
        }

        // 文本 message（block_type=10000）
        val textBlock = JSONObject().apply {
            put("block_type", 10000)
            put("content", JSONObject().apply {
                put("text_block", JSONObject().apply {
                    put("text", prompt)
                    put("icon_url", "")
                    put("icon_url_dark", "")
                    put("summary", "")
                })
                put("pc_event_block", "")
            })
            put("block_id", uuid())
            put("parent_id", "")
            put("meta_info", JSONArray())
            put("append_fields", JSONArray())
        }

        return JSONObject().apply {
            put("client_meta", JSONObject().apply {
                put("conversation_id", convId)
                put("bot_id", EXTENSION_BOT_ID)
                put("last_section_id", sectionId)
                put("last_message_index", 0)
            })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("local_message_id", localMessageId)
                    put("content_block", JSONArray().apply { put(imageBlock) })
                    put("message_status", 0)
                })
                put(JSONObject().apply {
                    put("local_message_id", uuid())
                    put("content_block", JSONArray().apply { put(textBlock) })
                    put("message_status", 0)
                })
            })
            put("option", JSONObject().apply {
                put("send_message_scene", "")
                put("create_time_ms", System.currentTimeMillis())
                put("collect_id", "")
                put("is_audio", false)
                put("answer_with_suggest", false)
                put("tts_switch", false)
                put("need_deep_think", 0)
                put("click_clear_context", false)
                put("from_suggest", false)
                put("is_regen", false)
                put("is_replace", false)
                put("is_from_click_option", false)
                put("is_from_click_softlink", false)
                put("disable_sse_cache", false)
                put("select_text_action", "")
                put("is_select_text", false)
                put("resend_for_regen", false)
                put("scene_type", 0)
                put("unique_key", uuid())
                put("start_seq", 0)
                put("need_create_conversation", false)
                put("regen_query_id", JSONArray())
                put("edit_query_id", JSONArray())
                put("regen_instruction", "")
                put("no_replace_for_regen", false)
                put("message_from", 0)
                put("shared_app_name", "")
                put("shared_app_id", "")
                put("sse_recv_event_options", JSONObject().apply { put("support_chunk_delta", true) })
                put("is_ai_playground", false)
                put("is_old_user", true)
                put("recovery_option", JSONObject().apply {
                    put("is_recovery", false)
                    put("req_create_time_sec", System.currentTimeMillis() / 1000)
                    put("append_sse_event_scene", 0)
                })
                put("message_storage_type", 0)
                put("related_deleted_message_ids", JSONObject())
                put("connector_info_list", JSONArray())
                put("model_config", JSONObject().apply {
                    put("model_item_key", "")
                    put("model_extra_params", JSONObject())
                })
                put("aggregate_params", JSONObject().apply {
                    put("model_item_key", "")
                    put("provider_id", "")
                })
            })
            put("chat_ability", JSONObject().apply {
                put("ability_type", 3)
                put("ability_param", "{\"ability_param\":{\"model\":\"Seedream 4.5\",\"ratio\":\"auto\"},\"ability_type\":1}")
            })
            put("user_context", JSONArray())
            put("ext", JSONObject().apply {
                put("answer_with_suggest", "0")
                put("collection_id", "")
                put("commerce_credit_config_enable", "0")
            })
        }.toString()
    }

    /**
     * 生成图片（同步，读取完整响应后解析）。
     */
    fun generateImage(
        prompt: String,
        cookies: Map<String, String>,
        ratio: String? = null,
        refImages: List<RefImage> = emptyList(),
    ): DoubaoImageResult {
        val params = securityParams()
        val cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        val csrfToken = cookies["passport_csrf_token"] ?: cookies["passport_csrf_token_default"] ?: ""

        // 参考图：必须走 ImageX 直传（tos-cn-i- uri），samantha upload_image 的 ocean-cloud-tos uri 不支持生图参考图
        val hasRefs = refImages.isNotEmpty()
        val uploadedRefs = if (hasRefs) {
            refImages.map { ref ->
                // ext 从 mime 推导（JPEG 字节配 jpeg、PNG 配 png）
                val ext = when {
                    ref.mime.contains("png", ignoreCase = true) -> "png"
                    ref.mime.contains("webp", ignoreCase = true) -> "webp"
                    else -> "jpeg"
                }
                uploadImageImageX(ref.bytes, ext, cookies)
            }
        } else emptyList()
        if (hasRefs) LogStore.i("Doubao", "参考图上传完成 uris=${uploadedRefs.map { it.uri }}")

        // 带参考图走新版协议（create_section 建会话 → pre_handle_v2 绑定 → chat/completion）；
        // 纯文本仍走 samantha（已验证）
        val (url, body) = if (hasRefs) {
            // 1) 建会话拿 conversation_id + section_id（无聊天记录）
            val (convId, sectionId) = createConversationSection(cookies)
            LogStore.i("Doubao", "生图建会话 convId=$convId sectionId=$sectionId")
            // 2) pre_handle_v2 绑定参考图 key（identifier 与 attachment.identifier 一致）
            val localMessageId = uuid()
            val identifier = uuid()
            uploadedRefs.firstOrNull()?.let { ref ->
                preHandleV2(ref.uri, convId, sectionId, localMessageId, identifier, cookies)
            }
            "$BASE_URL/chat/completion?${buildQueryString(params)}" to
                buildImageGenPayloadWithRefs(prompt, ratio, uploadedRefs, convId, sectionId, localMessageId, identifier)
        } else {
            "$BASE_URL/samantha/chat/completion?${buildQueryString(params)}" to
                buildImageGenPayload(prompt, ratio)
        }

        val reqBuilder = Request.Builder().url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("User-Agent", UA)
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/chat")
            .header("Cookie", cookieHeader)
        if (hasRefs) reqBuilder.header("x-tt-passport-csrf-token", csrfToken)
        val req = reqBuilder.build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                throw RuntimeException("豆包生图 HTTP ${resp.code}: ${err.take(300)}")
            }
            val bodyText = resp.body!!.string()
            LogStore.i("Doubao", "生图响应 len=${bodyText.length} 含2074=${bodyText.contains("2074")} 含raw=${bodyText.contains("image_ori_raw")}")
            // 解析新版响应（patch_op + block_type 2074 creation_block）或旧版 samantha（event_type 2001 + content_type 2010）
            val images = if (hasRefs) parseNewGenResponse(bodyText) else parseSamanthaGenResponse(bodyText)
            LogStore.i("Doubao", "生图解析到图片数=${images.size}")
            return DoubaoImageResult(images, prompt)
        }
    }

    /** 解析新版 /chat/completion 生图响应：patch_op[].patch_value.content_block[].block_type=2074 creation_block。 */
    private fun parseNewGenResponse(bodyText: String): List<DoubaoImage> {
        val images = mutableListOf<DoubaoImage>()
        for (block in bodyText.split("\n\n")) {
            if (block.isBlank()) continue
            var dataStr = ""
            for (line in block.trim().lineSequence()) {
                if (line.startsWith("data:")) dataStr = line.substring(5).trim()
            }
            if (dataStr.isBlank() || dataStr == "{}") continue
            try {
                val data = JSONObject(dataStr)
                if (data.optInt("event_type") == 2005) {
                    throw RuntimeException("豆包生图错误: ${data.opt("event_data")}")
                }
                val blocks = mutableListOf<JSONObject>()
                val patchOps = data.optJSONArray("patch_op")
                if (patchOps != null) {
                    for (i in 0 until patchOps.length()) {
                        val pv = patchOps.optJSONObject(i)?.optJSONObject("patch_value") ?: continue
                        val cb = pv.optJSONArray("content_block") ?: continue
                        for (j in 0 until cb.length()) blocks.add(cb.getJSONObject(j))
                    }
                }
                // 也看顶层 content_block（部分事件直接带）
                val cbTop = data.optJSONArray("content_block")
                if (cbTop != null) for (j in 0 until cbTop.length()) blocks.add(cbTop.getJSONObject(j))
                for (cb in blocks) {
                    // 只收 is_finish=true 的完成块（生成图完整数据）
                    if (cb.optInt("block_type", 0) != 2074) continue
                    if (!cb.optBoolean("is_finish", false)) continue
                    val creations = cb.optJSONObject("content")
                        ?.optJSONObject("creation_block")
                        ?.optJSONArray("creations") ?: continue
                    for (k in 0 until creations.length()) {
                        val img = creations.optJSONObject(k)?.optJSONObject("image") ?: continue
                        val ori = img.optJSONObject("image_ori") ?: JSONObject()
                        val oriRaw = img.optJSONObject("image_ori_raw") ?: JSONObject()
                        val thumb = img.optJSONObject("image_thumb") ?: JSONObject()
                        val key = img.optString("key", "")
                        if (key.isBlank()) continue
                        images.add(DoubaoImage(
                            key = key,
                            oriUrl = ori.optString("url", ""),
                            rawUrl = oriRaw.optString("url", ""),
                            thumbUrl = thumb.optString("url", ""),
                            width = ori.optInt("width", thumb.optInt("width", 0)),
                            height = ori.optInt("height", thumb.optInt("height", 0)),
                            format = key.substringAfterLast('.', "").ifBlank { "jpeg" }
                        ))
                    }
                }
            } catch (e: Exception) {
                if (e.message?.startsWith("豆包生图错误") == true) throw e
            }
        }
        return images
    }

    /** 解析旧版 samantha 生图响应：event_type 2001 + content_type 2010 + content.data[].image_ori。 */
    private fun parseSamanthaGenResponse(bodyText: String): List<DoubaoImage> {
        val images = mutableListOf<DoubaoImage>()
        for (block in bodyText.split("\n\n")) {
            if (block.isBlank()) continue
            var dataStr = ""
            for (line in block.trim().lineSequence()) {
                if (line.startsWith("data:")) dataStr = line.substring(5).trim()
            }
            if (dataStr.isBlank()) continue
            try {
                val data = JSONObject(dataStr)
                if (data.optInt("event_type") == 2005) {
                    throw RuntimeException("豆包生图错误: ${data.opt("event_data")}")
                }
                if (data.optInt("event_type") != 2001) continue
                val ed = if (data.opt("event_data") is String) JSONObject(data.getString("event_data")) else data.optJSONObject("event_data") ?: JSONObject()
                val msg = ed.optJSONObject("message") ?: JSONObject()
                if (msg.optInt("content_type") != 2010) continue
                val content = if (msg.opt("content") is String) JSONObject(msg.getString("content")) else msg.optJSONObject("content") ?: JSONObject()
                for (i in 0 until (content.optJSONArray("data")?.length() ?: 0)) {
                    val item = content.optJSONArray("data")!!.optJSONObject(i) ?: continue
                    val ori = item.optJSONObject("image_ori") ?: JSONObject()
                    val raw = item.optJSONObject("image_raw") ?: JSONObject()
                    val thumb = item.optJSONObject("image_thumb") ?: JSONObject()
                    images.add(DoubaoImage(
                        key = item.optString("key", ""),
                        oriUrl = ori.optString("url", ""),
                        rawUrl = raw.optString("url", ""),
                        thumbUrl = thumb.optString("url", ""),
                        width = ori.optInt("width", thumb.optInt("width", 0)),
                        height = ori.optInt("height", thumb.optInt("height", 0)),
                        format = ori.optString("format", thumb.optString("format", ""))
                    ))
                }
            } catch (e: Exception) {
                if (e.message?.startsWith("豆包生图错误") == true) throw e
            }
        }
        return images
    }
}
