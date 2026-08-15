package com.github.jing332.deepseekproxy.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

data class BrowserRelayActivationRequest(
    val sequence: Long = 0,
    val modelId: String = "",
)

/**
 * Bridges local API requests to the single retained browser WebView.
 *
 * External clients cannot click MultiWeb's browser-provider picker. Publishing
 * the requested model here lets the Compose browser host switch pages while the
 * request waits for the corresponding automation adapter to attach.
 */
object BrowserRelayActivation {
    private val sequence = AtomicLong()
    private val _requests = MutableStateFlow(BrowserRelayActivationRequest())
    val requests: StateFlow<BrowserRelayActivationRequest> = _requests.asStateFlow()

    fun activate(modelId: String) {
        if (modelId.isBlank()) return
        _requests.value = BrowserRelayActivationRequest(
            sequence = sequence.incrementAndGet(),
            modelId = modelId,
        )
    }

    suspend fun <T : Any> activateAndAwait(
        modelId: String,
        timeoutMs: Long = 60_000L,
        current: () -> T?,
    ): T? {
        current()?.let { return it }
        activate(modelId)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(200)
            current()?.let { return it }
        }
        return null
    }
}
