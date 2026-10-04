package com.wenwei.deepseekproxy.proxy

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A single writer serializes response chunks and SSE comments while the website thinks. */
internal suspend fun withWebChatSse(
    channel: ByteWriteChannel,
    keepAlive: Boolean,
    heartbeatIntervalMs: Long = 10_000,
    block: suspend (suspend (String) -> Unit) -> Unit,
) = coroutineScope {
    val mutex = Mutex()
    val write: suspend (String) -> Unit = { value ->
        mutex.withLock {
            channel.writeFully(value.toByteArray(Charsets.UTF_8))
            channel.flush()
        }
    }
    val heartbeat = if (keepAlive) launch {
        while (isActive) {
            delay(heartbeatIntervalMs)
            write(": keep-alive\n\n")
        }
    } else null
    try {
        block(write)
    } finally {
        heartbeat?.cancelAndJoin()
    }
}

@Serializable
private data class WebChatErrorDetail(val type: String, val code: String, val message: String)

@Serializable
private data class WebChatErrorEvent(val error: WebChatErrorDetail)

internal fun webChatErrorJson(error: Exception, type: String = "webview_automation_error"): String = Json.encodeToString(
    WebChatErrorEvent.serializer(),
    WebChatErrorEvent(WebChatErrorDetail(
        type, (error as? WebAutomationException)?.code ?: error.javaClass.simpleName,
        error.message ?: "网页调用失败",
    )),
)
