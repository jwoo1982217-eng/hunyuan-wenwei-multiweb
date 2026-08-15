package com.github.jing332.deepseekproxy.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local truth published only by explicit start requests and Service lifecycle callbacks. */
object LocalGatewayServiceLifecycle {
    private val _running = MutableStateFlow(false)
    private val _lastError = MutableStateFlow<String?>(null)

    val running = _running.asStateFlow()
    val lastError = _lastError.asStateFlow()

    fun startRequested() {
        _lastError.value = null
        // Do not claim running until ProxyService confirms ProxyServer.start succeeded.
        if (!ProxyServer.isRunning()) _running.value = false
    }

    fun startSucceeded() {
        _lastError.value = null
        _running.value = true
    }

    fun startFailed(message: String) {
        _lastError.value = message.ifBlank { "Local gateway failed to start" }
        _running.value = ProxyServer.isRunning()
    }

    fun stopped() {
        _running.value = false
    }

    fun reconcileWithRuntime() {
        _running.value = ProxyServer.isRunning()
    }
}
