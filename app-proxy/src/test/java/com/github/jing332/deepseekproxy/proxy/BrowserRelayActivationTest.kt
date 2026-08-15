package com.github.jing332.deepseekproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRelayActivationTest {
    @Test
    fun `activation publishes every external model request`() {
        val before = BrowserRelayActivation.requests.value.sequence

        BrowserRelayActivation.activate("arena-ai-image-web")
        val first = BrowserRelayActivation.requests.value
        BrowserRelayActivation.activate("longcat-image-web")
        val second = BrowserRelayActivation.requests.value

        assertEquals("arena-ai-image-web", first.modelId)
        assertEquals("longcat-image-web", second.modelId)
        assertTrue(first.sequence > before)
        assertTrue(second.sequence > first.sequence)
    }
}
