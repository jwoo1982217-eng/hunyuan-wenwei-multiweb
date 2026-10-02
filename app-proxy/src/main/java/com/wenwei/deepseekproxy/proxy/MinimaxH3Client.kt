package com.wenwei.deepseekproxy.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * MiniMax-H3 视频客户端（siftq.com 匿名试用通道，文生+图生）。
 *
 * 协议（自 h3-gateway 移植，2026-10-02 真机链路逐项验证）：
 *  1. 提交：POST https://siftq.com/api/minimax-trial/showcase/video-generation
 *     multipart 字段：visitorId(mmguest_<uuid>) / channelCode=direct / sourceHost=siftq.com /
 *     showcase_id=case-mtqzygu8 / ratio=9:16 / duration(6|10|15) / client_id(mmtrial_<uuid>) /
 *     prompt；图生多一个 image 文件部件。头：X-MiniMax-Trial-Client / X-Forwarded-For /
 *     Idempotency-Key / Referer(minimax-h3/try/zh)
 *  2. "无限"机制：上游配额按伪造 X-Forwarded-For IP 记（2 次/天/IP）+ 自造 client_id——
 *     每个任务现编身份；401 login_required / 429 rate_limit 均换新身份重试（实测 401 偶发属正常）
 *  3. 轮询：GET /video-generation/{task_id}?access_token=…，status=running→succeeded
 *     （响应不含 URL）；轮询头带 X-MiniMax-Trial-Client + XFF 更稳
 *  4. 成片：GET /video-generation/{task_id}/content?client_id=…&access_token=…
 *     裸头（仅 UA+Referer）即可 200 下载 mp4（实测 1.2MB）——返回给客户端的直链可直接播
 *
 * 限制：ratio 仅 9:16（其他 400）；duration 归一到 6/10/15；任务 1-2 天后被上游回收。
 */
object MinimaxH3Client {
    private const val BASE = "https://siftq.com/api/minimax-trial"
    private const val SHOWCASE_ID = "case-mtqzygu8"
    private const val REFERER = "https://siftq.com/minimax-h3/try/zh"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private const val MAX_SUBMIT_RETRY = 6
    private const val POLL_INTERVAL_MS = 5000L
    private const val MAX_POLL = 60   // ≈5 分钟

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 一个伪造身份：client_id + visitorId + 假 IP（配额记账单位） */
    private class Ident(val clientId: String, val visitorId: String, val forgedIp: String) {
        fun headers(): Map<String, String> = mapOf(
            "X-MiniMax-Trial-Client" to clientId,
            "X-Forwarded-For" to forgedIp,
        )
    }

    private fun mint(): Ident {
        val cid = "mmtrial_${UUID.randomUUID()}"
        val vid = "mmguest_${UUID.randomUUID()}"
        // 避开内网/保留段
        var a: Int
        var b: Int
        do {
            a = (1..223).random()
            b = (0..255).random()
        } while (a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254))
        return Ident(cid, vid, "$a.$b.${(0..255).random()}.${(1..254).random()}")
    }

    /** 秒数归一：4-7→6，8-12→10，13-20→15，其余取 6 */
    fun normalizeDuration(raw: Int?): Int = when (raw ?: 6) {
        in 4..7 -> 6
        in 8..12 -> 10
        in 13..20 -> 15
        else -> 6
    }

    private fun multipartBody(fields: List<Pair<String, String>>, boundary: String,
                              imageBytes: ByteArray?, imageExt: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        for ((k, v) in fields) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        if (imageBytes != null) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; " +
                "filename=\"upload.$imageExt\"\r\nContent-Type: image/jpeg\r\n\r\n")
            out.write(imageBytes)
            w("\r\n")
        }
        w("--$boundary--\r\n")
        return out.toByteArray()
    }

    /**
     * 生成视频，返回成片直链（含查询参数，裸头可下载）。
     * imageBytes 为 null → 文生；否则图生。ratio 上游仅支持 9:16。
     */
    fun generateVideo(prompt: String, durationSec: Int, imageBytes: ByteArray?, imageExt: String): String {
        var lastErr = "提交失败"
        for (attempt in 1..MAX_SUBMIT_RETRY) {
            val ident = mint()
            val dur = normalizeDuration(durationSec)
            val fields = listOf(
                "visitorId" to ident.visitorId,
                "channelCode" to "direct",
                "sourceHost" to "siftq.com",
                "showcase_id" to SHOWCASE_ID,
                "ratio" to "9:16",
                "duration" to dur.toString(),
                "client_id" to ident.clientId,
                "prompt" to prompt.ifBlank { "Animate the scene with natural, faithful motion." },
            )
            val boundary = "----H3MW${UUID.randomUUID().toString().replace("-", "")}"
            val body = multipartBody(fields, boundary, imageBytes, imageExt)
            val req = Request.Builder()
                .url("$BASE/showcase/video-generation")
                .post(body.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Origin", "https://siftq.com")
                .header("Referer", REFERER)
                .header("Idempotency-Key", "mmtrial_${UUID.randomUUID()}")
                .apply { ident.headers().forEach { (k, v) -> header(k, v) } }
                .build()

            try {
                client.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.code == 200) {
                        val j = runCatching { JSONObject(text) }.getOrNull()
                        val taskId = j?.optString("task_id").orEmpty()
                        val token = j?.optString("access_token").orEmpty()
                        if (taskId.isNotBlank() && token.isNotBlank()) {
                            LogStore.i("H3", "任务已提交: $taskId dur=${dur}s 图生=${imageBytes != null}（第 $attempt 次尝试）")
                            return pollAndGetUrl(taskId, token, ident)
                        }
                        lastErr = "响应缺字段: ${text.take(150)}"
                    } else if (r.code == 401 || r.code == 429) {
                        // login_required（偶发）/ rate_limit（该假 IP 额度尽）→ 换新身份重试
                        lastErr = "HTTP ${r.code}: ${text.take(120)}"
                        LogStore.w("H3", "第 $attempt 次被拒（$lastErr）— 换身份重试")
                    } else {
                        throw RuntimeException("H3 提交失败 HTTP ${r.code}: ${text.take(200)}")
                    }
                }
            } catch (e: RuntimeException) {
                throw e
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
                LogStore.w("H3", "提交异常: $lastErr — 重试")
            }
        }
        throw RuntimeException("H3 多次提交失败: $lastErr")
    }

    private fun pollAndGetUrl(taskId: String, accessToken: String, ident: Ident): String {
        for (i in 1..MAX_POLL) {
            Thread.sleep(POLL_INTERVAL_MS)
            val req = Request.Builder()
                .url("$BASE/video-generation/$taskId?access_token=$accessToken")
                .get()
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Referer", REFERER)
                .apply { ident.headers().forEach { (k, v) -> header(k, v) } }
                .build()
            try {
                client.newCall(req).execute().use { r ->
                    if (r.code != 200) {
                        LogStore.w("H3", "轮询 HTTP ${r.code}")
                        continue
                    }
                    val j = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull() ?: continue
                    when (j.optString("status")) {
                        "succeeded", "success", "completed" -> {
                            // 成片直链：查询参数带 client_id + access_token，裸头（UA+Referer）可下载
                            return "$BASE/video-generation/$taskId/content" +
                                "?client_id=${ident.clientId}&access_token=$accessToken"
                        }
                        "failed", "error", "cancelled" ->
                            throw RuntimeException("H3 任务失败: ${j.optString("message").ifBlank { "上游拒绝" }}")
                    }
                }
            } catch (e: java.lang.InterruptedException) {
                throw e
            } catch (e: RuntimeException) {
                throw e
            } catch (_: Exception) {
            }
        }
        throw RuntimeException("H3 任务超时（${MAX_POLL} 次轮询未完成）")
    }

    /** 图生参考图：http(s) 直链下载兜底（base64 由调用方先解） */
    fun fetchImageBytes(url: String): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
            .execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()
}
