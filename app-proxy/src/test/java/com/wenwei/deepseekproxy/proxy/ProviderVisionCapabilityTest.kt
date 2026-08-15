package com.wenwei.deepseekproxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderVisionCapabilityTest {
    private fun api(id: String) = ProviderModel(
        publicModelId = id,
        displayName = id,
        providerType = WebProviderType.OPENAI_COMPATIBLE,
        capabilities = setOf(ProviderCapability.CHAT),
        upstreamModelId = id
    )

    @Test fun recognizesVisionModelsAndRejectsTextOnlyModels() {
        assertTrue(api("meta/llama-3.2-11b-vision-instruct").supportsImageInput)
        assertTrue(api("nvidia/nemotron-nano-12b-v2-vl").supportsImageInput)
        assertTrue(api("gemini-2.0-flash").supportsImageInput)
        assertFalse(api("deepseek-v4-flash").supportsImageInput)
        assertFalse(ProviderCatalog.find("hy3-preview")!!.supportsImageInput)
    }
}
