package com.github.jing332.deepseekproxy.proxy

import java.util.concurrent.ConcurrentHashMap

data class ApiRouteHealth(
    val latencyMs: Long = Long.MAX_VALUE,
    val success: Boolean = false,
    val checkedAt: Long = 0L,
    val consecutiveFailures: Int = 0,
    val cooldownUntil: Long = 0L
)

/** Ranks providers serving the same upstream model. Explicit provider models bypass this selector. */
object ApiRouteSelector {
    private val health = ConcurrentHashMap<String, ApiRouteHealth>()

    fun record(providerId: String, success: Boolean, latencyMs: Long, retryableFailure: Boolean = true) {
        val previous = health[providerId] ?: ApiRouteHealth()
        val failures = if (success) 0 else previous.consecutiveFailures + 1
        val smoothed = if (success && previous.latencyMs != Long.MAX_VALUE)
            ((previous.latencyMs * 2) + latencyMs) / 3 else latencyMs
        health[providerId] = ApiRouteHealth(
            latencyMs = smoothed,
            success = success,
            checkedAt = System.currentTimeMillis(),
            consecutiveFailures = failures,
            cooldownUntil = if (!success && retryableFailure) System.currentTimeMillis() + 30_000L else 0L
        )
    }

    fun status(providerId: String): ApiRouteHealth? = health[providerId]

    fun rank(configs: List<ApiProviderConfig>): List<ApiProviderConfig> {
        val now = System.currentTimeMillis()
        return configs.sortedWith(compareBy<ApiProviderConfig>(
            { (health[it.id]?.cooldownUntil ?: 0L) > now },
            { !(health[it.id]?.success ?: false) },
            { health[it.id]?.latencyMs ?: Long.MAX_VALUE },
            { it.name.lowercase() }
        ))
    }
}
