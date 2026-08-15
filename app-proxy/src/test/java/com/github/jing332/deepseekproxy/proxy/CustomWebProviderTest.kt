package com.github.jing332.deepseekproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomWebProviderTest {
    private fun config(modelId: String, imageOnly: Boolean = false) = CustomWebProviderConfig(
        name = "Custom",
        modelId = modelId,
        loginUrl = "https://example.com/chat",
        inputSelector = "",
        sendSelector = "",
        responseSelector = "",
        imageProvider = imageOnly,
    )

    @Test
    fun `chat web model gets a distinct image alias`() {
        assertEquals("example-image-web", customWebImageModelId(config("example-web")))
        assertEquals("example-image", customWebImageModelId(config("example")))
    }

    @Test
    fun `image-only web model keeps its public id`() {
        assertEquals("example-web", customWebImageModelId(config("example-web", imageOnly = true)))
    }

    @Test
    fun `configured selector is guarded and falls back to semantic discovery`() {
        val expression = configuredElementExpression(
            "Describe the image you want to generate...",
            "AUTO_DISCOVERY",
        )

        assertTrue(expression.contains("try{return document.querySelector"))
        assertTrue(expression.contains("catch(_)"))
        assertTrue(expression.endsWith("||(AUTO_DISCOVERY)"))
        assertEquals("AUTO_DISCOVERY", configuredElementExpression("", "AUTO_DISCOVERY"))
    }

    @Test
    fun `arena data stream parser joins answer chunks and ignores reasoning`() {
        val raw = """
            ag:"thinking"
            a0:"Arena "
            a0:"guest"
            e:{"finishReason":"stop"}
        """.trimIndent()

        assertEquals("Arena guest", parseArenaTextStream(raw))
    }

    @Test
    fun `arena parser accepts older zero-prefixed text chunks`() {
        assertEquals("pong", parseArenaTextStream("0:\"pong\""))
    }

    @Test
    fun `existing custom arena provider also uses guest stream adapter`() {
        val custom = config("arena-ai-web").copy(loginUrl = "https://arena.ai/")
        assertEquals(true, isArenaGuestWebConfig(custom))
        assertEquals(
            false,
            isArenaGuestWebConfig(custom.copy(loginUrl = "https://arena.ai.evil.example/")),
        )
    }

    @Test
    fun `legacy arena home URL migrates to direct chat`() {
        assertEquals("https://arena.ai/text/direct", normalizeArenaGuestUrl("https://arena.ai/"))
        assertEquals(
            "https://arena.ai/text/direct",
            normalizeArenaGuestUrl("https://arena.ai/text/direct"),
        )
        assertEquals("https://arena.ai.evil.example/", normalizeArenaGuestUrl("https://arena.ai.evil.example/"))
    }

    @Test
    fun `arena generated image accepts signed r2 output`() {
        assertEquals(
            true,
            isArenaGeneratedImageUrl(
                "https://messages-prod.example.r2.cloudflarestorage.com/session/output.jpeg?X-Amz-Signature=abc"
            ),
        )
        assertEquals(
            true,
            isArenaGeneratedImageUrl(
                "https://messages-prod.example.r2.cloudflarestorage.com/session/output.png"
            ),
        )
    }

    @Test
    fun `arena generated image rejects lookalike and non-image assets`() {
        assertEquals(
            false,
            isArenaGeneratedImageUrl("https://r2.cloudflarestorage.com.evil.example/output.png"),
        )
        assertEquals(
            false,
            isArenaGeneratedImageUrl("https://arena.ai/images/favicon.svg"),
        )
    }
}
