package com.github.jing332.deepseekproxy.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

data class HiApiImageResult(val httpCode: Int, val body: String)

/** Adapter for HiAPI's unified asynchronous image task API. */
object HiApiImageClient {
    /** Text-to-image models published on HiAPI's official pricing page. */
    internal val textToImageModels = listOf(
        "grok-imagine/text-to-image",
        "seedream-5.0-pro/text-to-image",
        "Nano-Banana-2-Lite",
        "nano-banana-2",
        "gpt-image-2/text-to-image@ext",
        "qwen-image-2.0",
        "z-image",
        "gpt-image-2/text-to-image@beta",
        "wan2.7-image/text-to-image",
        "grok-imagine-quality/text-to-image",
        "seedream-5.0-lite/text-to-image",
        "ideogram-v4",
        "flux-2/text-to-image",
        "flux-1.1-pro",
        "seedream-4.5/text-to-image",
        "gpt-image-2/text-to-image",
        "nano-banana",
        "flux-schnell/text-to-image",
        "qwen-image-2.0-pro",
        "nano-banana-pro",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    internal fun matches(config: ApiProviderConfig): Boolean = runCatching {
        URI(config.normalizedBaseUrl()).host.orEmpty().let {
            it.equals("hiapi.ai", true) || it.endsWith(".hiapi.ai", true)
        }
    }.getOrDefault(false)

    internal fun taskPayload(upstreamModel: String, rawRequest: String): JSONObject {
        val raw = JSONObject(rawRequest)
        val model = canonicalModel(upstreamModel)
        val input = JSONObject().put("prompt", raw.optString("prompt"))
        val ratio = normalizeRatio(raw.optString("size"))
        if (ratio.isNotBlank()) input.put("aspect_ratio", ratio)
        if (model.startsWith("gpt-image-2", true)) input.put("resolution", "1K")
        return JSONObject().put("model", model).put("input", input)
    }

    internal fun canonicalModel(value: String): String {
        val requested = value.trim()
        return when {
            requested.equals("gpt-image-1", true) || requested.equals("gpt-image-1.5", true) ->
                "gpt-image-2/text-to-image"
            else -> requested
        }
    }

    internal fun normalizeRatio(value: String): String {
        val trimmed = value.trim()
        if (trimmed.matches(Regex("\\d{1,2}:\\d{1,2}"))) return trimmed
        val match = Regex("(\\d{2,5})[xX](\\d{2,5})").matchEntire(trimmed) ?: return "1:1"
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        val known = listOf("1:1", "3:2", "2:3", "4:3", "3:4", "5:4", "4:5", "16:9", "9:16", "21:9")
        val target = width.toDouble() / height
        return known.minBy { ratio ->
            val p = ratio.split(':')
            kotlin.math.abs(p[0].toDouble() / p[1].toDouble() - target)
        }
    }

    fun generate(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): HiApiImageResult {
        val root = OpenAiCompatibleClient.apiRoot(config.normalizedBaseUrl())
        val tasksUrl = "$root/tasks"
        val createPayload = taskPayload(upstreamModel, rawRequest)
        val createBody = execute(config, Request.Builder().url(tasksUrl)
            .post(createPayload.toString().toRequestBody(JSON)))
        val createError = createBody.optJSONObject("error")
        if (createError != null) return errorResult(createError, createBody)
        val taskId = createBody.optJSONObject("data")?.optString("taskId").orEmpty()
        if (taskId.isBlank()) return HiApiImageResult(502, errorJson("invalid_task_response", createBody.toString()))

        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
        var last = createBody
        while (System.nanoTime() < deadline) {
            Thread.sleep(3_000)
            last = execute(config, Request.Builder().url("$tasksUrl/$taskId").get())
            last.optJSONObject("error")?.let { return errorResult(it, last) }
            val data = last.optJSONObject("data") ?: continue
            when (data.optString("status").lowercase()) {
                "success" -> {
                    val output = data.optJSONArray("output") ?: JSONArray()
                    val images = JSONArray()
                    for (i in 0 until output.length()) {
                        val url = output.optJSONObject(i)?.optString("url").orEmpty()
                        if (url.isNotBlank()) images.put(JSONObject().put("url", url))
                    }
                    if (images.length() == 0) return HiApiImageResult(502, errorJson("missing_image_url", last.toString()))
                    return HiApiImageResult(200, JSONObject()
                        .put("created", System.currentTimeMillis() / 1000)
                        .put("data", images).toString())
                }
                "fail", "failed", "error" -> {
                    val error = data.optJSONObject("error") ?: JSONObject().put("message", last.toString())
                    return errorResult(error, last)
                }
            }
        }
        return HiApiImageResult(504, errorJson("task_timeout", "HiAPI task $taskId did not finish within 10 minutes"))
    }

    private fun execute(config: ApiProviderConfig, builder: Request.Builder): JSONObject {
        val request = builder
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("error", JSONObject()
                    .put("code", "http_${response.code}")
                    .put("message", text.ifBlank { response.message }))
            }
        }
    }

    private fun errorResult(error: JSONObject, full: JSONObject): HiApiImageResult {
        val code = error.optString("code", "hiapi_error")
        val status = when (code.lowercase()) {
            "permission_denied", "unauthorized" -> 401
            "rate_limited", "rate_limit_exceeded" -> 429
            "invalid_request", "invalid_model" -> 400
            else -> 502
        }
        return HiApiImageResult(status, JSONObject().put("error", JSONObject()
            .put("type", "hiapi_error")
            .put("code", code)
            .put("message", error.optString("message", full.toString()))).toString())
    }

    private fun errorJson(code: String, message: String): String = JSONObject().put("error", JSONObject()
        .put("type", "hiapi_error").put("code", code).put("message", message)).toString()

    private val JSON = "application/json".toMediaType()
}
