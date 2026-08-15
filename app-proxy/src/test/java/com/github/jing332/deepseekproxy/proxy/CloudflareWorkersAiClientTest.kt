package com.github.jing332.deepseekproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareWorkersAiClientTest {

    @Test
    fun detectsWorkersPaidPlanRestrictionOnlyOn403() {
        val body = """{"errors":[{"message":"Model x is not available on the Workers Free plan: This model requires a Workers Paid plan."}]}"""
        assertTrue(CloudflareWorkersAiClient.isPaidPlanRestriction(403, body))
        assertFalse(CloudflareWorkersAiClient.isPaidPlanRestriction(400, body))
        assertFalse(CloudflareWorkersAiClient.isPaidPlanRestriction(403, "missing prompt"))
    }

    @Test
    fun detectsCloudflareFlux2MultipartModels() {
        assertTrue(CloudflareWorkersAiClient.usesMultipartInput("@cf/black-forest-labs/flux-2-klein-9b"))
        assertTrue(CloudflareWorkersAiClient.usesMultipartInput("@cf/black-forest-labs/flux-2-dev"))
        assertFalse(CloudflareWorkersAiClient.usesMultipartInput("@cf/black-forest-labs/flux-1-schnell"))
    }
    private val config = ApiProviderConfig(
        name = "Cloudflare AI",
        baseUrl = "https://api.cloudflare.com/client/v4/accounts/account-123/ai/v1",
        apiKey = "secret"
    )

    @Test
    fun recognizesCloudflareAccountUrl() {
        assertTrue(CloudflareWorkersAiClient.matches(config))
        assertEquals("account-123", CloudflareWorkersAiClient.accountId(config))
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/account-123/ai",
            CloudflareWorkersAiClient.apiBase(config)
        )
    }

    @Test
    fun parsesTextAndImageModelsFromSearchResponse() {
        val result = CloudflareWorkersAiClient.parseModelSearch(
            """
            {
              "success": true,
              "result": [
                {"name":"@cf/meta/llama-test","task":{"name":"Text Generation"}},
                {"name":"@cf/black-forest-labs/flux-test","task":{"name":"Text-to-Image"}},
                {"name":"@cf/runway/model-inpainting","task":{"name":"Text-to-Image"}},
                {"name":"@cf/openai/whisper-test","task":{"name":"Automatic Speech Recognition"},"description":"Generates text transcripts"}
              ]
            }
            """.trimIndent()
        )

        assertTrue(result.success)
        assertEquals(listOf("@cf/meta/llama-test"), result.chatModels)
        assertEquals(listOf("@cf/black-forest-labs/flux-test"), result.imageModels)
    }
}
