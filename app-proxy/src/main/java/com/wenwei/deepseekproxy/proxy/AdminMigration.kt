package com.wenwei.deepseekproxy.proxy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置迁移（移植自墨🐳 v1.0.13 AdminMigration.kt）：
 * 把「接口」页的 OpenAI 兼容供应商 + 自定义 Web 供应商整包导出/导入，
 * 走 HTTP 管理接口 /admin/export、/admin/import-api、/admin/import-web，
 * 也供 ProviderBackup 本地备份复用。密钥经 Store 序列化时照常加密落盘，
 * 导出内容是明文（调用方须提示保管）。
 */
object AdminMigration {
    const val SCHEMA = "mw-admin-export/1"

    @Volatile private var ctx: Context? = null

    fun attach(context: Context) { ctx = context.applicationContext }

    private fun requireCtx(): Context =
        ctx ?: throw IllegalStateException("AdminMigration 未 attach")

    private fun apiStore() = ApiProviderStore(requireCtx())
    private fun webStore() = CustomWebProviderStore(requireCtx())

    /** 全量导出（schema + apiProviders + customWebProviders） */
    fun exportJson(): String {
        val apiArray = JSONArray()
        apiStore().load().forEach { config ->
            apiArray.put(JSONObject().apply {
                put("id", config.id)
                put("name", config.name)
                put("baseUrl", config.baseUrl)
                put("apiKey", config.apiKey)
                put("chatModels", JSONArray(config.chatModels))
                put("imageModels", JSONArray(config.imageModels))
                put("videoModels", JSONArray(config.videoModels))
                put("enabled", config.enabled)
            })
        }
        val webArray = JSONArray()
        webStore().load().forEach { config ->
            webArray.put(JSONObject().apply {
                put("id", config.id)
                put("name", config.name)
                put("modelId", config.modelId)
                put("loginUrl", config.loginUrl)
                put("inputSelector", config.inputSelector)
                put("sendSelector", config.sendSelector)
                put("responseSelector", config.responseSelector)
                put("imageProvider", config.imageProvider)
            })
        }
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("apiProviders", apiArray)
            put("customWebProviders", webArray)
        }.toString()
    }

    /**
     * 导入接口供应商。body 支持裸数组或 {apiProviders:[...]} 对象；
     * mode=replace 清空后导入，默认 merge 按 id 更新/追加。
     * 返回 {"ok":true,"imported":N,"skipped":[...],"total":N}。
     */
    fun importApi(body: String): String {
        val configs = extractArray(body, "apiProviders") ?: return err("请求体不是合法的接口配置 JSON")
        val replace = isReplace(body)
        val existing = if (replace) mutableListOf() else apiStore().load().toMutableList()
        val skipped = JSONArray()
        var ok = 0
        for (i in 0 until configs.length()) {
            val item = configs.optJSONObject(i) ?: continue
            try {
                val name = item.optString("name").trim()
                val baseUrl = item.optString("baseUrl").trim()
                if (name.isBlank() || !baseUrl.startsWith("http")) {
                    skipped.put(name.ifBlank { "unnamed" })
                    continue
                }
                val config = ApiProviderConfig(
                    id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = item.optString("apiKey"),
                    chatModels = item.optJSONArray("chatModels").strings(),
                    imageModels = item.optJSONArray("imageModels").strings(),
                    videoModels = item.optJSONArray("videoModels").strings(),
                    enabled = item.optBoolean("enabled", true),
                )
                val index = existing.indexOfFirst { it.id == config.id }
                if (index >= 0) existing[index] = config else existing.add(config)
                ok++
            } catch (e: Exception) {
                skipped.put(item.optString("name").ifBlank { "error" })
            }
        }
        existing.forEach { apiStore().save(it) }
        if (replace) {
            // merge 已在上面整体写入；replace 需要删掉未出现在导入清单里的旧条目
            val keep = existing.map { it.id }.toSet()
            apiStore().load().filterNot { it.id in keep }.forEach { apiStore().delete(it.id) }
        }
        refreshRuntimeModels()
        return result(ok, skipped, configs.length())
    }

    /** 导入自定义 Web 供应商，规则同 importApi（key=customWebProviders） */
    fun importWeb(body: String): String {
        val configs = extractArray(body, "customWebProviders") ?: return err("请求体不是合法的 Web 配置 JSON")
        val replace = isReplace(body)
        val existing = if (replace) mutableListOf() else webStore().load().toMutableList()
        val skipped = JSONArray()
        var ok = 0
        for (i in 0 until configs.length()) {
            val item = configs.optJSONObject(i) ?: continue
            try {
                val config = CustomWebProviderConfig(
                    id = item.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    name = item.optString("name"),
                    modelId = item.optString("modelId"),
                    loginUrl = item.optString("loginUrl"),
                    inputSelector = item.optString("inputSelector"),
                    sendSelector = item.optString("sendSelector"),
                    responseSelector = item.optString("responseSelector"),
                    imageProvider = item.optBoolean("imageProvider", false),
                )
                if (config.name.isBlank() || config.modelId.isBlank()) {
                    skipped.put(item.optString("name").ifBlank { "unnamed" })
                    continue
                }
                val index = existing.indexOfFirst { it.id == config.id }
                if (index >= 0) existing[index] = config else existing.add(config)
                ok++
            } catch (e: Exception) {
                skipped.put(item.optString("name").ifBlank { "error" })
            }
        }
        if (replace) {
            val keep = existing.map { it.id }.toSet()
            webStore().load().filterNot { it.id in keep }.forEach { webStore().delete(it.id) }
        }
        existing.forEach { webStore().save(it) }
        refreshRuntimeModels()
        return result(ok, skipped, configs.length())
    }

    /** 按 Store 当前内容重建运行时模型表（与接口页保存后的刷新一致） */
    fun refreshRuntimeModels() {
        val configs = apiStore().load()
        ApiProviderRegistry.set(configs)
        val byId = buildApiProviderModels(configs).associateBy { it.publicModelId.lowercase() }
        val kept = ProviderCatalog.runtimeModelsSnapshot().filterNot {
            it.providerType == WebProviderType.OPENAI_COMPATIBLE && byId.containsKey(it.publicModelId.lowercase())
        }
        ProviderCatalog.setRuntimeModels(kept + byId.values)
    }

    /** OpenAI 兼容供应商 → 运行时 ProviderModel（与接口页保存路径同构） */
    fun buildApiProviderModels(configs: List<ApiProviderConfig>): List<ProviderModel> {
        val models = mutableListOf<ProviderModel>()
        configs.filter { it.enabled }.forEach { config ->
            config.chatModels.distinct().forEach { id ->
                models.add(ProviderModel(
                    id, "${config.name} · $id", WebProviderType.OPENAI_COMPATIBLE,
                    setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
                    false, id, "auto",
                ))
            }
            config.imageModels.distinct().forEach { id ->
                models.add(ProviderModel(
                    id, "${config.name} · $id", WebProviderType.OPENAI_COMPATIBLE,
                    setOf(ProviderCapability.IMAGE_GENERATION),
                    false, id, "auto",
                ))
            }
            config.videoModels.distinct().forEach { id ->
                models.add(ProviderModel(
                    id, "${config.name} · $id", WebProviderType.OPENAI_COMPATIBLE,
                    setOf(ProviderCapability.VIDEO_GENERATION),
                    false, id, "auto",
                ))
            }
        }
        return models
    }

    private fun extractArray(body: String, key: String): JSONArray? = runCatching {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) JSONArray(trimmed)
        else JSONObject(trimmed).optJSONArray(key)
    }.getOrNull()

    private fun isReplace(body: String): Boolean = runCatching {
        val trimmed = body.trim()
        trimmed.startsWith("{") && JSONObject(trimmed).optString("mode", "merge") == "replace"
    }.getOrDefault(false)

    private fun JSONArray?.strings(): List<String> = this?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
    }.orEmpty().distinct()

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg).toString()

    private fun result(ok: Int, skipped: JSONArray, total: Int) = JSONObject().apply {
        put("ok", true)
        put("imported", ok)
        put("skipped", skipped)
        put("total", total)
    }.toString()
}
