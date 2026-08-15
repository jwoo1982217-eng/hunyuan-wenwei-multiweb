package com.github.jing332.deepseekproxy.proxy

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class GatewayReadiness {
    READY,
    LOGIN_REQUIRED,
    WEBVIEW_REQUIRED,
    UNAVAILABLE,
    UNTESTED,
}

@Serializable
data class GatewayStatusModel(
    val id: String,
    val displayName: String,
    val provider: String,
    val capabilities: List<String>,
    val readiness: GatewayReadiness,
    val detail: String,
)

@Serializable
data class GatewayCapabilityStatus(
    val name: String,
    val readiness: GatewayReadiness,
    val modelIds: List<String>,
)

@Serializable
data class GatewayProviderStatus(
    val id: String,
    val displayName: String,
    val readiness: GatewayReadiness,
    val loginReadiness: GatewayReadiness,
    val webViewReadiness: GatewayReadiness,
    val detail: String,
)

@Serializable
data class LocalGatewayStatusResponse(
    val schemaVersion: Int = 1,
    val service: String = "multiweb-local-gateway",
    val serviceStatus: GatewayReadiness,
    val bindHost: String,
    val port: Int,
    val authRequired: Boolean = true,
    val models: List<GatewayStatusModel>,
    val capabilities: List<GatewayCapabilityStatus>,
    val providers: List<GatewayProviderStatus>,
)

data class GatewayRuntimeSnapshot(
    val cnbAuthenticated: Boolean = false,
    val doubaoLoggedIn: Boolean = false,
    val authenticatedSessionProviders: Set<WebProviderType> = emptySet(),
    val attachedChatProviderId: String? = null,
    val attachedImageProviderId: String? = null,
    val modelHealth: Map<String, Boolean?> = emptyMap(),
)

/** Runtime evidence only: route registration or credentials never count as a successful provider call. */
object GatewayModelHealth {
    private val results = ConcurrentHashMap<String, Boolean>()

    fun record(publicModelId: String, success: Boolean) {
        if (publicModelId.isNotBlank()) results[publicModelId] = success
    }

    fun status(publicModelId: String): Boolean? = results[publicModelId]

    fun snapshot(publicModelIds: Iterable<String>): Map<String, Boolean?> =
        publicModelIds.associateWith(::status)

    suspend fun <T> recordInvocation(publicModelId: String, invocation: suspend () -> T): T = try {
        invocation().also { record(publicModelId, true) }
    } catch (failure: Throwable) {
        record(publicModelId, false)
        throw failure
    }

    internal fun clear() = results.clear()
}

/** Converts live prerequisites into explicit, externally consumable readiness states. */
object LocalGatewayStatusResolver {
    fun readyModels(models: List<ProviderModel>, snapshot: GatewayRuntimeSnapshot): List<ProviderModel> =
        models.filter { modelStatus(it, snapshot).readiness == GatewayReadiness.READY }

    fun modelStatus(model: ProviderModel, snapshot: GatewayRuntimeSnapshot): GatewayStatusModel {
        val readiness = readiness(model, snapshot)
        return GatewayStatusModel(
            id = model.publicModelId,
            displayName = model.displayName,
            provider = providerId(model),
            capabilities = model.capabilities.map { it.name }.sorted(),
            readiness = readiness,
            detail = detail(model, readiness),
        )
    }

    fun build(
        models: List<ProviderModel>,
        snapshot: GatewayRuntimeSnapshot,
        bindHost: String,
        port: Int,
    ): LocalGatewayStatusResponse {
        val modelStatuses = models.map { modelStatus(it, snapshot) }
        val capabilities = ProviderCapability.entries.map { capability ->
            val matching = modelStatuses.filter { capability.name in it.capabilities }
            GatewayCapabilityStatus(
                name = capability.name,
                readiness = aggregate(matching.map(GatewayStatusModel::readiness)),
                modelIds = matching.filter { it.readiness == GatewayReadiness.READY }.map { it.id },
            )
        }
        val byProvider = models.zip(modelStatuses).groupBy { (_, status) -> status.provider }
        val providers = byProvider.map { (id, pairs) ->
            val first = pairs.first().first
            val statuses = pairs.map { it.second }
            GatewayProviderStatus(
                id = id,
                displayName = first.providerType.displayName,
                readiness = aggregate(statuses.map { it.readiness }),
                loginReadiness = loginReadiness(first, snapshot),
                webViewReadiness = webViewReadiness(first, snapshot),
                detail = statuses.firstOrNull { it.readiness != GatewayReadiness.READY }?.detail
                    ?: "Provider prerequisites are ready",
            )
        }.sortedBy { it.displayName.lowercase() }
        return LocalGatewayStatusResponse(
            serviceStatus = GatewayReadiness.READY,
            bindHost = bindHost,
            port = port,
            models = modelStatuses,
            capabilities = capabilities,
            providers = providers,
        )
    }

    private fun readiness(model: ProviderModel, snapshot: GatewayRuntimeSnapshot): GatewayReadiness {
        if (loginReadiness(model, snapshot) == GatewayReadiness.LOGIN_REQUIRED) {
            return GatewayReadiness.LOGIN_REQUIRED
        }
        if (webViewReadiness(model, snapshot) == GatewayReadiness.WEBVIEW_REQUIRED) {
            return GatewayReadiness.WEBVIEW_REQUIRED
        }
        if (model.providerType == WebProviderType.CNB && !snapshot.cnbAuthenticated) {
            return GatewayReadiness.UNTESTED
        }
        return when (snapshot.modelHealth[model.publicModelId]) {
            true -> GatewayReadiness.READY
            false -> GatewayReadiness.UNAVAILABLE
            null -> GatewayReadiness.UNTESTED
        }
    }

    private fun loginReadiness(model: ProviderModel, snapshot: GatewayRuntimeSnapshot): GatewayReadiness = when (model.providerType) {
        WebProviderType.DOUBAO_WEB -> if (snapshot.doubaoLoggedIn) GatewayReadiness.READY else GatewayReadiness.LOGIN_REQUIRED
        WebProviderType.CHATGPT_WEB,
        WebProviderType.GEMINI_WEB,
        WebProviderType.KIMI_WEB,
        WebProviderType.DEEPSEEK_WEB,
        WebProviderType.QWEN_WEB -> if (model.providerType in snapshot.authenticatedSessionProviders) {
            GatewayReadiness.READY
        } else {
            GatewayReadiness.LOGIN_REQUIRED
        }
        WebProviderType.QWEN_AI_WEB,
        WebProviderType.MINIMAX_WEB,
        WebProviderType.MIMO_WEB,
        WebProviderType.PERPLEXITY_WEB,
        WebProviderType.ZAI_WEB,
        WebProviderType.ARENA_GUEST,
        WebProviderType.CUSTOM_WEB -> GatewayReadiness.UNTESTED
        else -> GatewayReadiness.READY
    }

    private fun webViewReadiness(model: ProviderModel, snapshot: GatewayRuntimeSnapshot): GatewayReadiness {
        val requiredId = when (model.providerType) {
            WebProviderType.CHATGPT_WEB -> if (ProviderCapability.IMAGE_GENERATION in model.capabilities) {
                "image:CHATGPT_WEB"
            } else {
                "chat:CHATGPT_WEB"
            }
            WebProviderType.GEMINI_WEB -> if (ProviderCapability.IMAGE_GENERATION in model.capabilities) {
                "image:GEMINI_WEB"
            } else {
                "chat:GEMINI_WEB"
            }
            WebProviderType.RAPHAEL_WEB -> "image:RAPHAEL_WEB"
            WebProviderType.CUSTOM_WEB -> if (ProviderCapability.IMAGE_GENERATION in model.capabilities) {
                "image:${model.accountId}"
            } else {
                "chat:${model.accountId}"
            }
            WebProviderType.QWEN_AI_WEB,
            WebProviderType.MINIMAX_WEB,
            WebProviderType.MIMO_WEB,
            WebProviderType.PERPLEXITY_WEB,
            WebProviderType.ZAI_WEB,
            WebProviderType.ARENA_GUEST -> "chat:builtin:${model.providerType.name}"
            else -> return GatewayReadiness.READY
        }
        val actual = if (requiredId.startsWith("image:")) {
            snapshot.attachedImageProviderId?.let { "image:$it" }
        } else {
            snapshot.attachedChatProviderId?.let { "chat:$it" }
        }
        return if (requiredId == actual) GatewayReadiness.READY else GatewayReadiness.WEBVIEW_REQUIRED
    }

    private fun detail(model: ProviderModel, readiness: GatewayReadiness): String = when (readiness) {
        GatewayReadiness.READY -> "Provider prerequisites are ready"
        GatewayReadiness.LOGIN_REQUIRED -> "Sign in to ${model.providerType.displayName} and save the session"
        GatewayReadiness.WEBVIEW_REQUIRED -> "Open ${model.providerType.displayName} in MultiWeb and keep its WebView attached"
        GatewayReadiness.UNAVAILABLE -> "The latest provider health check failed"
        GatewayReadiness.UNTESTED -> "Run an actual provider test before assigning this model"
    }

    private fun providerId(model: ProviderModel): String =
        model.accountId?.let { "${model.providerType.name.lowercase()}:$it" }
            ?: model.providerType.name.lowercase()

    fun aggregate(states: List<GatewayReadiness>): GatewayReadiness = when {
        states.any { it == GatewayReadiness.READY } -> GatewayReadiness.READY
        states.any { it == GatewayReadiness.LOGIN_REQUIRED } -> GatewayReadiness.LOGIN_REQUIRED
        states.any { it == GatewayReadiness.WEBVIEW_REQUIRED } -> GatewayReadiness.WEBVIEW_REQUIRED
        states.any { it == GatewayReadiness.UNTESTED } -> GatewayReadiness.UNTESTED
        states.isNotEmpty() -> GatewayReadiness.UNAVAILABLE
        else -> GatewayReadiness.UNAVAILABLE
    }
}
