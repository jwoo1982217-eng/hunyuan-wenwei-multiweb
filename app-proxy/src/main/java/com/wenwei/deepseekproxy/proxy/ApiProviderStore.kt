package com.wenwei.deepseekproxy.proxy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ApiProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val chatModels: List<String> = emptyList(),
    val imageModels: List<String> = emptyList(),
    val videoModels: List<String> = emptyList(),
    val enabled: Boolean = true
) {
    fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/')
}

/** Encrypted persistent storage for user-defined OpenAI-compatible endpoints. */
class ApiProviderStore(context: Context) {
    private val prefs = context.getSharedPreferences("api_provider_configs", Context.MODE_PRIVATE)
    private val cipher = SessionSecretCipher()

    fun load(): List<ApiProviderConfig> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim()
            val baseUrl = item.optString("baseUrl").trim()
            if (name.isBlank() || baseUrl.isBlank()) return@mapNotNull null
            ApiProviderConfig(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = name,
                baseUrl = baseUrl,
                apiKey = item.optString("apiKey").takeIf(String::isNotBlank)?.let(cipher::decrypt).orEmpty(),
                chatModels = item.optJSONArray("chatModels").strings(),
                imageModels = item.optJSONArray("imageModels").strings(),
                videoModels = item.optJSONArray("videoModels").strings(),
                enabled = item.optBoolean("enabled", true)
            )
        }
    }.getOrDefault(emptyList())

    fun save(config: ApiProviderConfig) {
        val items = load().toMutableList()
        val index = items.indexOfFirst { it.id == config.id }
        if (index >= 0) items[index] = config else items += config
        persist(items)
    }

    fun delete(id: String) = persist(load().filterNot { it.id == id })

    private fun persist(configs: List<ApiProviderConfig>) {
        val array = JSONArray()
        configs.forEach { config -> array.put(JSONObject().apply {
            put("id", config.id)
            put("name", config.name)
            put("baseUrl", config.baseUrl)
            put("apiKey", config.apiKey.takeIf(String::isNotBlank)?.let(cipher::encrypt).orEmpty())
            put("chatModels", JSONArray(config.chatModels.distinct()))
            put("imageModels", JSONArray(config.imageModels.distinct()))
            put("videoModels", JSONArray(config.videoModels.distinct()))
            put("enabled", config.enabled)
        }) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun JSONArray?.strings(): List<String> = this?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
    }.orEmpty().distinct()

    companion object { private const val KEY = "providers_v1" }
}

object ApiProviderRegistry {
    @Volatile private var providers: Map<String, ApiProviderConfig> = emptyMap()
    fun set(configs: List<ApiProviderConfig>) { providers = configs.associateBy { it.id } }
    fun find(id: String?): ApiProviderConfig? = id?.let(providers::get)
    fun candidates(upstreamModelId: String, image: Boolean = false): List<ApiProviderConfig> = providers.values
        .filter { config -> config.enabled && (if (image) config.imageModels else config.chatModels)
            .any { it.equals(upstreamModelId, ignoreCase = true) } }
}
