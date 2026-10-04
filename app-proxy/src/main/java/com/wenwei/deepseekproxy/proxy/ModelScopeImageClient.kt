package com.wenwei.deepseekproxy.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 魔搭 ModelScope 异步生图通道（移植自墨🐳 v1.0.13 ModelScopeImageClient.kt）：
 * 命中规则 = 接口供应商 baseUrl 含 "modelscope"。把 OpenAI 的
 * /v1/images/generations 请求翻译成 ModelScope 的异步任务：
 *   POST {root}/tasks（X-ModelScope-Async-Mode: true + X-ModelScope-Task-Type）
 *   → task_id → 轮询 GET {root}/tasks/{id} 到 SUCCEED → output_images[]。
 * 兼容 https://api-inference.modelscope.cn/v1（生图）与视频任务端点。
 */
object ModelScopeImageClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 该接口供应商是否走魔搭适配 */
    fun matches(config: ApiProviderConfig): Boolean =
        config.baseUrl.contains("modelscope", ignoreCase = true)

    /** 规范化根地址：剥掉端点后缀，保证以 /v1 结尾 */
    private fun apiRoot(address: String): String {
        var root = address.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/images/generations", "/tasks")) {
            if (root.endsWith(suffix, ignoreCase = true)) {
                root = root.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        val hasVersion = (1..6).any { root.endsWith("/v$it", ignoreCase = true) }
        return if (hasVersion) root else "$root/v1"
    }

    private fun relayError(url: String, code: Int, type: String, message: String): Response {
        val body = JSONObject().put("error", JSONObject().put("type", type).put("message", message)).toString()
        return Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code).message(message)
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    /**
     * 生图：入参 OpenAI 请求原文，返回 OpenAI 生图响应（data[].url）。
     * videoType 非空时走视频任务（供视频线复用同一 task 协议）。
     */
    fun generate(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): Response {
        val stubUrl = config.normalizedBaseUrl().ifBlank { "https://modelscope.local" }
        val root = apiRoot(config.baseUrl)
        val apiKey = config.apiKey.trim()

        val request = runCatching { JSONObject(rawRequest) }.getOrElse {
            return relayError(stubUrl, 400, "invalid_request", "请求体不是合法 JSON: ${it.message}")
        }
        val prompt = request.optString("prompt").trim()
            .ifBlank {
                // 兼容 messages 形态的请求
                val msgs = request.optJSONArray("messages")
                (msgs?.length()?.downTo(0)?.toList() ?: emptyList())
                    .firstNotNullOfOrNull { i -> msgs?.optJSONObject(i)?.optString("content")?.takeIf(String::isNotBlank) }
                    ?: ""
            }
        if (prompt.isBlank()) {
            return relayError(stubUrl, 400, "missing_prompt", "缺少 prompt")
        }
        val taskBody = JSONObject().apply {
            put("model", upstreamModel)
            put("prompt", prompt)
            request.optString("size").takeIf(String::isNotBlank)?.let { put("size", it) }
            request.optInt("seed", -1).takeIf { it >= 0 }?.let { put("seed", it) }
        }

        // 1) 提交任务（官方异步生图：POST {root}/images/generations + Async-Mode 头）
        val submitReq = Request.Builder().url("$root/images/generations")
            .post(taskBody.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                header("Authorization", "Bearer $apiKey")
                header("X-ModelScope-Async-Mode", "true")
            }.build()
        val submitResp = runCatching { client.newCall(submitReq).execute() }.getOrElse {
            return relayError(stubUrl, 502, "submit_failed", "任务提交失败: ${it.message}")
        }
        val submitText = submitResp.body?.string().orEmpty().also { submitResp.close() }
        if (!submitResp.isSuccessful) {
            return relayError(stubUrl, if (submitResp.code == 401) 401 else 502, "submit_failed",
                "任务提交 HTTP ${submitResp.code}: ${submitText.take(200)}")
        }
        val taskId = runCatching { JSONObject(submitText).optString("task_id") }.getOrDefault("")
        if (taskId.isBlank()) {
            return relayError(stubUrl, 502, "missing_task_id", "任务提交响应缺 task_id: ${submitText.take(200)}")
        }
        LogStore.i("ModelScope", "提交成功 task_id=$taskId (image)")

        // 2) 轮询到完成（对齐墨🐳：5 秒间隔，10 分钟上限）
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        val pollReq = Request.Builder().url("$root/tasks/$taskId")
            .header("Authorization", "Bearer $apiKey")
            .header("X-ModelScope-Task-Type", "image_generation")
            .build()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            val resp = runCatching { client.newCall(pollReq).execute() }.getOrElse { continue }
            val text = resp.body?.string().orEmpty().also { resp.close() }
            if (!resp.isSuccessful) continue
            val job = runCatching { JSONObject(text) }.getOrNull() ?: continue
            when (job.optString("task_status")) {
                "SUCCEED" -> {
                    val raw = job.optJSONArray("output_images")
                        ?: job.optJSONArray("output_image")
                        ?: org.json.JSONArray()
                    val data = org.json.JSONArray()
                    for (i in 0 until raw.length()) {
                        when (val item = raw.opt(i)) {
                            is String -> if (item.isNotBlank()) data.put(JSONObject().put("url", item))
                            is JSONObject -> {
                                val url = item.optString("url", "").ifBlank { item.optString("image_url", "") }
                                if (url.isNotBlank()) data.put(JSONObject().put("url", url))
                            }
                        }
                    }
                    if (data.length() == 0) {
                        return relayError(stubUrl, 502, "no_url_in_output", "任务完成但没有图片地址: ${text.take(200)}")
                    }
                    val okBody = JSONObject().put("created", System.currentTimeMillis() / 1000).put("data", data).toString()
                    return Response.Builder()
                        .request(Request.Builder().url(stubUrl).build())
                        .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                        .body(okBody.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                "FAILED", "CANCELED" -> {
                    val reason = job.optString("errors", "").ifBlank { job.optString("message", "任务失败") }
                    return relayError(stubUrl, 502, "task_failed", "$reason (${text.take(150)})")
                }
            }
        }
        return relayError(stubUrl, 504, "task_timeout", "任务超时未完成 task_id=$taskId")
    }
}
