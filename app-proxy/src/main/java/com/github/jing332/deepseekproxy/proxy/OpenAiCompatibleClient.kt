package com.github.jing332.deepseekproxy.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ApiTestResult(
    val success: Boolean,
    val message: String,
    val chatModels: List<String> = emptyList(),
    val imageModels: List<String> = emptyList(),
    val latencyMs: Long = 0L
) {
    val models: List<String> get() = (chatModels + imageModels).distinct()
}

object OpenAiCompatibleClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    internal fun apiRoot(address: String): String {
        var value = address.trim().trimEnd('/')
        listOf("/chat/completions", "/images/generations", "/models").firstOrNull {
            value.endsWith(it, ignoreCase = true)
        }?.let { suffix -> value = value.dropLast(suffix.length).trimEnd('/') }
        return if (value.endsWith("/v1", ignoreCase = true)) value else "$value/v1"
    }

    internal fun endpoint(config: ApiProviderConfig, path: String): String {
        return "${apiRoot(config.normalizedBaseUrl())}/$path"
    }

    private fun Request.Builder.authorize(config: ApiProviderConfig): Request.Builder = apply {
        if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
    }

    suspend fun test(config: ApiProviderConfig): ApiTestResult = withContext(Dispatchers.IO) {
        if (CloudflareWorkersAiClient.matches(config)) {
            return@withContext CloudflareWorkersAiClient.test(config)
        }
        val startedAt = System.nanoTime()
        runCatching {
            client.newCall(Request.Builder().url(endpoint(config, "models")).authorize(config).get().build())
                .execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return@use ApiTestResult(
                        false,
                        "HTTP ${response.code}: ${body.take(180)}",
                        latencyMs = (System.nanoTime() - startedAt) / 1_000_000
                    )
                    val parsed = normalizeProviderModels(config, parseModelList(body))
                    parsed.copy(
                        message = "${parsed.message}，${(System.nanoTime() - startedAt) / 1_000_000}ms",
                        latencyMs = (System.nanoTime() - startedAt) / 1_000_000
                    )
                }
        }.getOrElse { ApiTestResult(
            false, "网络异常: ${it.message ?: it.javaClass.simpleName}",
            latencyMs = (System.nanoTime() - startedAt) / 1_000_000
        ) }
    }

    internal fun parseModelList(body: String): ApiTestResult {
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
        val chat = mutableListOf<String>()
        val images = mutableListOf<String>()
        for (element in data) {
            val item = runCatching { element.jsonObject }.getOrNull() ?: continue
            val id = item["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (id.isBlank()) continue
            val metadata = element.toString().lowercase()
            val explicitImage = listOf("image_generation", "image-generation", "text_to_image", "text-to-image")
                .any(metadata::contains) || item["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .contains("image", ignoreCase = true)
            val explicitChat = listOf("chat.completions", "chat_completion", "text_generation", "text-generation")
                .any(metadata::contains)
            val imageById = IMAGE_MODEL_ID.containsMatchIn(id)
            if (explicitImage || imageById) images += id
            if (explicitChat || (!explicitImage && !imageById)) chat += id
        }
        val allCount = (chat + images).distinct().size
        return ApiTestResult(
            success = true,
            message = "连接成功，发现 $allCount 个模型（聊天 ${chat.distinct().size}，生图 ${images.distinct().size}）",
            chatModels = chat.distinct(),
            imageModels = images.distinct()
        )
    }

    internal fun normalizeProviderModels(config: ApiProviderConfig, result: ApiTestResult): ApiTestResult {
        if (!HiApiImageClient.matches(config)) return result
        val current = result.imageModels
            .filterNot { it.equals("gpt-image-1", true) || it.equals("gpt-image-1.5", true) }
            .filterNot { it.contains("/image-to-image", true) }
            .toMutableList()
        HiApiImageClient.textToImageModels.forEach { model ->
            if (current.none { it.equals(model, true) }) current += model
        }
        val allCount = (result.chatModels + current).distinct().size
        return result.copy(
            message = "连接成功，发现 $allCount 个模型（聊天 ${result.chatModels.size}，生图 ${current.distinct().size}）",
            imageModels = current.distinct()
        )
    }

    private val IMAGE_MODEL_ID = Regex(
        "(^|[-_./])(gpt[-_]?image|dall[-_]?e|flux|sdxl|stable[-_]?diffusion|imagen|seedream|recraft|ideogram|kolors|cogview|nano[-_]?banana|image[-_]?gen)([-_./]|$)",
        RegexOption.IGNORE_CASE
    )

    fun executeChat(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): Response {
        val body = JSONObject(rawRequest).put("model", upstreamModel).toString()
        return client.newCall(Request.Builder().url(endpoint(config, "chat/completions"))
            .authorize(config).header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType())).build()).execute()
    }

    fun executeImage(config: ApiProviderConfig, upstreamModel: String, rawRequest: String): Response {
        if (CloudflareWorkersAiClient.matches(config)) {
            return CloudflareWorkersAiClient.generateImage(config, upstreamModel, rawRequest)
        }
        if (HiApiImageClient.matches(config)) {
            val result = HiApiImageClient.generate(config, upstreamModel, rawRequest)
            val request = Request.Builder().url(endpoint(config, "images/generations")).build()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(result.httpCode)
                .message(if (result.httpCode in 200..299) "OK" else "HiAPI task failed")
                .body(result.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val jsonBody = JSONObject(rawRequest).put("model", upstreamModel)
        fun execute(body: String): Response = client.newCall(
            Request.Builder().url(endpoint(config, "images/generations"))
                .authorize(config).header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType())).build()
        ).execute()

        var response = execute(jsonBody.toString())
        repeat(2) {
            if (response.isSuccessful) return response
            val errorPreview = response.peekBody(8_192).string()
            var changed = false

            // Compatible vendors do not agree on size values. If the upstream
            // advertises accepted pixels, preserve the requested aspect ratio.
            val requestedSize = jsonBody.optString("size")
            if (':' in requestedSize) {
                val supported = PIXEL_SIZE.findAll(errorPreview).map { it.value }.distinct().toList()
                closestPixelSize(requestedSize, supported)?.let { retrySize ->
                    jsonBody.put("size", retrySize)
                    changed = true
                }
            }

            // Some image relays cap prompts (commonly 512 CJK chars/words). Retry
            // conservatively by Unicode code point so Chinese and emoji stay valid.
            if (errorPreview.contains("prompt_too_long", ignoreCase = true) ||
                errorPreview.contains("prompt is too long", ignoreCase = true)
            ) {
                val max = PROMPT_RANGE.find(errorPreview)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val prompt = jsonBody.optString("prompt")
                if (max != null && max > 0) {
                    val shortened = truncateCodePoints(prompt, max)
                    if (shortened != prompt) {
                        jsonBody.put("prompt", shortened)
                        changed = true
                    }
                }
            }

            if (!changed) return response
            response.close()
            response = execute(jsonBody.toString())
        }
        return response
    }

    internal fun closestPixelSize(ratio: String, supported: List<String>): String? {
        val target = ratio.split(':').takeIf { it.size == 2 }
            ?.let { parts -> parts[0].toDoubleOrNull()?.div(parts[1].toDoubleOrNull() ?: return null) }
            ?: return null
        return supported.mapNotNull { size ->
            val match = PIXEL_SIZE.matchEntire(size) ?: return@mapNotNull null
            val width = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val height = match.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            size to kotlin.math.abs(width / height - target)
        }.minByOrNull { it.second }?.first
    }

    internal fun truncateCodePoints(text: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0) return ""
        val count = text.codePointCount(0, text.length)
        if (count <= maxCodePoints) return text
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints)).trimEnd()
    }

    private val PIXEL_SIZE = Regex("(\\d{2,5})x(\\d{2,5})", RegexOption.IGNORE_CASE)
    private val PROMPT_RANGE = Regex("between\\s*\\[\\s*\\d+\\s*,\\s*(\\d+)\\s*]", RegexOption.IGNORE_CASE)
}
