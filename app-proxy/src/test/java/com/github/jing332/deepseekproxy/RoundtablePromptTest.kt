package com.github.jing332.deepseekproxy

import org.junit.Assert.assertTrue
import org.junit.Test

class RoundtablePromptTest {
    @Test
    fun synthesisPromptCarriesQuestionAnswersAndPrivacyBoundary() {
        val prompt = buildRoundtableSynthesisPrompt(
            "应该采用哪个方案？",
            listOf("model-a" to "采用 A", "model-b" to "采用 B")
        )

        assertTrue(prompt.contains("应该采用哪个方案？"))
        assertTrue(prompt.contains("【model-a】\n采用 A"))
        assertTrue(prompt.contains("【model-b】\n采用 B"))
        assertTrue(prompt.contains("不要披露或推测任何隐藏思维过程"))
    }
}
