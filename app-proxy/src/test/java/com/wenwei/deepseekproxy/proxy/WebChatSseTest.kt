package com.wenwei.deepseekproxy.proxy

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class WebChatSseTest {
    @Test fun heartbeatDoesNotInterleaveOrCorruptUnicodeDataEvents() = runBlocking {
        withTimeout(5000) {
            val channel = ByteChannel(autoFlush = true)
            val lines = async {
                buildList {
                    while (true) add(channel.readUTF8Line() ?: break)
                }
            }
            withWebChatSse(channel, keepAlive = true, heartbeatIntervalMs = 10) { write ->
                write("data: {\"text\":\"你好\"}\n\n")
                delay(50)
                write("data: [DONE]\n\n")
            }
            channel.close()
            val result = lines.await()
            assertEquals(listOf("data: {\"text\":\"你好\"}", "data: [DONE]"), result.filter { it.startsWith("data:") })
            assertTrue(result.any { it == ": keep-alive" })
            assertTrue(result.all { it.isEmpty() || it.startsWith("data:") || it == ": keep-alive" })
        }
    }

    @Test fun ordinaryProvidersKeepTheirDataOnlyStream() = runBlocking {
        withTimeout(5000) {
            val channel = ByteChannel(autoFlush = true)
            val lines = async { buildList { while (true) add(channel.readUTF8Line() ?: break) } }
            withWebChatSse(channel, keepAlive = false, heartbeatIntervalMs = 10) { write ->
                write("data: one\n\n")
                delay(30)
                write("data: [DONE]\n\n")
            }
            channel.close()
            assertEquals(listOf("data: one", "", "data: [DONE]", ""), lines.await())
        }
    }
}
