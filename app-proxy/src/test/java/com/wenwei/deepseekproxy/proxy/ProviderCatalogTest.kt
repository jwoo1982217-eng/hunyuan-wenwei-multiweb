package com.wenwei.deepseekproxy.proxy

import org.junit.Assert.*
import org.junit.Test

class ProviderCatalogTest {

    @Test
    fun webRelayDiscoveryExcludesOrdinaryCompatibleApiModels() {
        val web = ProviderModel(
            publicModelId = "custom-web",
            displayName = "Custom Web",
            providerType = WebProviderType.CUSTOM_WEB,
            capabilities = setOf(ProviderCapability.CHAT),
        )
        val api = ProviderModel(
            publicModelId = "vendor--model",
            displayName = "API Model",
            providerType = WebProviderType.OPENAI_COMPATIBLE,
            capabilities = setOf(ProviderCapability.CHAT),
        )

        assertEquals(listOf(web), discoverableWebRelayModels(listOf(web, api)))
    }

    @Test
    fun includesQwenAndGuestZhipuCapabilities() {
        assertTrue(ProviderCatalog.chatModels.any { it.publicModelId == "qwen-web" })
        assertTrue(ProviderCatalog.chatModels.any { it.publicModelId == "qwen-think-web" })
        assertTrue(ProviderCatalog.chatModels.any { it.publicModelId == "zhipu-web" })
        assertTrue(ProviderCatalog.chatModels.any { it.publicModelId == "zhipu-plus-web" })
        assertTrue(ProviderCatalog.imageModels.any { it.publicModelId == "zhipu-image-web" })
        assertFalse(ProviderCatalog.chatModels.any { it.publicModelId == "zhipu-image-web" })
    }

    @Test
    fun includesBrowserLoginRelaysFromChat2ApiReference() {
        val ids = ProviderCatalog.chatModels.map { it.publicModelId }.toSet()
        assertTrue("qwen-ai-web" in ids)
        assertTrue("minimax-web" in ids)
        assertTrue("mimo-web" in ids)
        assertTrue("perplexity-web" in ids)
        assertTrue("zai-web" in ids)
        assertTrue(isBuiltInBrowserRelay(WebProviderType.MINIMAX_WEB))
        assertFalse(isBuiltInBrowserRelay(WebProviderType.ZHIPU_GUEST))
    }

    @Test
    fun arenaGuestIsAnExplicitNoLoginBrowserRelay() {
        val model = requireNotNull(ProviderCatalog.find("arena-guest-web"))
        assertEquals(WebProviderType.ARENA_GUEST, model.providerType)
        assertEquals(
            setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            model.capabilities,
        )
        assertEquals("https://arena.ai/text/direct", WebProviderType.ARENA_GUEST.loginUrl)
        assertTrue(isBuiltInBrowserRelay(WebProviderType.ARENA_GUEST))
        val config = builtInBrowserRelayConfig(WebProviderType.ARENA_GUEST)
        assertEquals("builtin:ARENA_GUEST", config.id)
        assertEquals("textarea[placeholder='Ask anything…']", config.inputSelector)
        assertEquals("button[type='submit']", config.sendSelector)
    }
    @Test fun enabledModelsKeepOriginalRelayModels() {
        val ids = ProviderCatalog.enabledModels.map { it.publicModelId }
        assertTrue(CnbClient.DEFAULT_MODEL in ids)
        assertTrue("doubao" in ids)
        assertTrue("doubao-pro" in ids)
    }

    @Test fun chatGptAndGeminiWebChatAreExposed() {
        val ids = ProviderCatalog.enabledModels.map { it.publicModelId }
        assertTrue("chatgpt-web" in ids)
        assertTrue("gemini-web" in ids)
        val chatGpt = ProviderCatalog.find("chatgpt-web")
        assertEquals(WebProviderType.CHATGPT_WEB, chatGpt?.providerType)
        assertTrue(ProviderCapability.CHAT in requireNotNull(chatGpt).capabilities)
        assertTrue(ProviderCapability.STREAMING in chatGpt.capabilities)
        assertFalse(ProviderCapability.IMAGE_GENERATION in chatGpt.capabilities)
        val gemini = requireNotNull(ProviderCatalog.find("gemini-web"))
        assertEquals(WebProviderType.GEMINI_WEB, gemini.providerType)
        assertTrue(ProviderCapability.CHAT in gemini.capabilities)
        assertTrue(ProviderCapability.STREAMING in gemini.capabilities)
        assertFalse(ProviderCapability.IMAGE_GENERATION in gemini.capabilities)
    }

    @Test fun kimiAndDeepSeekWebChatAreExposedWithoutChangingImageModels() {
        val kimi = requireNotNull(ProviderCatalog.find("kimi-web"))
        assertEquals(WebProviderType.KIMI_WEB, kimi.providerType)
        assertEquals(setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING), kimi.capabilities)

        val deepSeek = requireNotNull(ProviderCatalog.find("deepseek-web"))
        val reasoner = requireNotNull(ProviderCatalog.find("deepseek-reasoner-web"))
        assertEquals(WebProviderType.DEEPSEEK_WEB, deepSeek.providerType)
        assertEquals(WebProviderType.DEEPSEEK_WEB, reasoner.providerType)
        assertFalse(ProviderCatalog.imageModels.any {
            it.providerType == WebProviderType.KIMI_WEB || it.providerType == WebProviderType.DEEPSEEK_WEB
        })
    }

    @Test fun kimiCredentialExtractorAcceptsBearerAndCookieJwt() {
        assertEquals("abc", KimiClient.extractToken("Bearer abc").removePrefix("Bearer "))
        assertEquals("eyJ.test", KimiClient.extractToken("foo=1; access_token=eyJ.test; bar=2"))
    }

    @Test fun relayChatListDoesNotIncludeImageOnlyModels() {
        assertFalse(ProviderCatalog.chatModels.any { it.publicModelId == "doubao-image-web" })
        assertTrue(ProviderCatalog.chatModels.all { ProviderCapability.CHAT in it.capabilities })
    }

    @Test fun gptImage2IsExposedOnlyAsAnImageModel() {
        val model = ProviderCatalog.find("gpt-image-2")
        assertNotNull(model)
        assertEquals(WebProviderType.CHATGPT_WEB, model?.providerType)
        assertEquals(setOf(ProviderCapability.IMAGE_GENERATION), model?.capabilities)
        assertTrue(ProviderCatalog.imageModels.any { it.publicModelId == "gpt-image-2" })
        assertFalse(ProviderCatalog.chatModels.any { it.publicModelId == "gpt-image-2" })
    }

    @Test fun geminiImageWebIsExposedOnlyAsAnImageModel() {
        val model = requireNotNull(ProviderCatalog.find("gemini-image-web"))
        assertEquals(WebProviderType.GEMINI_WEB, model.providerType)
        assertEquals(setOf(ProviderCapability.IMAGE_GENERATION), model.capabilities)
        assertTrue(ProviderCatalog.imageModels.any { it.publicModelId == "gemini-image-web" })
        assertFalse(ProviderCatalog.chatModels.any { it.publicModelId == "gemini-image-web" })
    }

    @Test fun raphaelFreeWebIsExposedOnlyAsAnImageModel() {
        val model = requireNotNull(ProviderCatalog.find("raphael-image-web"))
        assertEquals(WebProviderType.RAPHAEL_WEB, model.providerType)
        assertEquals("raphael-basic", model.upstreamModelId)
        assertEquals(setOf(ProviderCapability.IMAGE_GENERATION), model.capabilities)
        assertTrue(ProviderCatalog.imageModels.any { it.publicModelId == "raphael-image-web" })
        assertFalse(ProviderCatalog.chatModels.any { it.publicModelId == "raphael-image-web" })
        assertEquals("https://raphael.app/zh", WebProviderType.RAPHAEL_WEB.loginUrl)
    }

    @Test fun geminiImageInstructionKeepsPromptAndRatio() {
        val instruction = buildGeminiImageInstruction("画一朵玫瑰", "9:16")
        assertTrue(instruction.contains("画一朵玫瑰"))
        assertTrue(instruction.contains("9:16"))
        assertTrue(instruction.contains("图片生成工具"))
        assertTrue(instruction.startsWith("用户原始画面描述：画一朵玫瑰。"))
        assertFalse(instruction.contains('\n'))
    }

    @Test fun geminiRenderedImageCropScalesAndClampsToWebView() {
        assertEquals(
            ImageCropBounds(left = 100, top = 200, width = 400, height = 600),
            scaledImageCrop(
                left = 50.0, top = 100.0, width = 200.0, height = 300.0,
                viewportWidth = 500.0, viewportHeight = 1000.0,
                bitmapWidth = 1000, bitmapHeight = 2000
            )
        )
        assertEquals(
            ImageCropBounds(left = 0, top = 0, width = 1000, height = 2000),
            scaledImageCrop(
                left = -20.0, top = -10.0, width = 600.0, height = 1200.0,
                viewportWidth = 500.0, viewportHeight = 1000.0,
                bitmapWidth = 1000, bitmapHeight = 2000
            )
        )
    }

    @Test fun disabledDiscoveredWebTextModelIsNotAdvertised() {
        ProviderCatalog.setRuntimeModels(listOf(ProviderModel(
            publicModelId = "chatgpt_web--discovered-only",
            displayName = "discovered-only",
            providerType = WebProviderType.CHATGPT_WEB,
            capabilities = setOf(ProviderCapability.CHAT),
            enabled = false
        )))
        assertNull(ProviderCatalog.find("chatgpt_web--discovered-only"))
        assertFalse(ProviderCatalog.enabledModels.any { it.publicModelId == "chatgpt_web--discovered-only" })
    }

    @Test fun eachWebProviderHasIndependentLoginOrigins() {
        val doubao = WebProviderType.DOUBAO_WEB.cookieOrigins
        val chatgpt = WebProviderType.CHATGPT_WEB.cookieOrigins
        val gemini = WebProviderType.GEMINI_WEB.cookieOrigins
        assertTrue(doubao.all { "doubao" in it })
        assertTrue(chatgpt.none { "doubao" in it })
        assertTrue(chatgpt.any { "auth.openai.com" in it })
        assertTrue(gemini.none { "doubao" in it || "chatgpt" in it })
        assertTrue(gemini.any { it == "https://gemini.google.com" })
        assertTrue(gemini.any { it == "https://accounts.google.com" })
        assertTrue(gemini.any { it == "https://www.google.com" })
    }

    @Test fun cookieSnapshotMergesAndDeduplicatesWithoutEmptyParts() {
        assertEquals(
            "session=a; csrf=b",
            WebCookieSnapshot.merge(listOf("session=a; csrf=b", "session=a; ", ""))
        )
    }

    @Test fun chatGptSessionTokenIsReadFromSessionJson() {
        assertEquals("token-value", WebCookieSnapshot.chatGptAccessToken("{\"accessToken\":\"token-value\"}"))
        assertEquals("", WebCookieSnapshot.chatGptAccessToken("{\"user\":{}}"))
    }

    @Test fun doubaoVisitorCookiesAreNotAcceptedAsLogin() {
        assertFalse(WebCookieSnapshot.hasDoubaoLoginCookie("flow_user_country=CN; biz_trace_id=abc"))
        assertTrue(WebCookieSnapshot.hasDoubaoLoginCookie("flow_user_country=CN; sessionid_ss=secret"))
        assertTrue(WebCookieSnapshot.hasDoubaoLoginCookie("SID_GUARD=secret"))
    }

    @Test fun chatGptRuntimeModelNeverResolvesAsCnb() {
        ProviderCatalog.setRuntimeModels(
            listOf(
                ProviderModel(
                    "chatgpt_web--gpt-test",
                    "gpt-test",
                    WebProviderType.CHATGPT_WEB,
                    setOf(ProviderCapability.CHAT),
                    upstreamModelId = "gpt-test",
                    accountId = "account-1"
                )
            )
        )
        assertEquals(WebProviderType.CHATGPT_WEB, ProviderCatalog.find("chatgpt_web--gpt-test")?.providerType)
    }

    @Test fun customApiModelKeepsProviderAndUpstreamRouting() {
        val model = ProviderModel(
            publicModelId = "my-vendor--chat-model",
            displayName = "chat-model",
            providerType = WebProviderType.OPENAI_COMPATIBLE,
            capabilities = setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
            upstreamModelId = "vendor-chat-v2",
            accountId = "provider-1"
        )
        ProviderCatalog.setRuntimeModels(listOf(model))
        val resolved = requireNotNull(ProviderCatalog.find("my-vendor--chat-model"))
        assertEquals(WebProviderType.OPENAI_COMPATIBLE, resolved.providerType)
        assertEquals("vendor-chat-v2", resolved.upstreamModelId)
        assertEquals("provider-1", resolved.accountId)
    }

    @Test fun customApiImageModelIsNotAdvertisedAsChat() {
        ProviderCatalog.setRuntimeModels(listOf(ProviderModel(
            publicModelId = "my-vendor--image-model",
            displayName = "image-model",
            providerType = WebProviderType.OPENAI_COMPATIBLE,
            capabilities = setOf(ProviderCapability.IMAGE_GENERATION),
            upstreamModelId = "image-v3",
            accountId = "provider-1"
        )))
        assertTrue(ProviderCatalog.imageModels.any { it.publicModelId == "my-vendor--image-model" })
        assertFalse(ProviderCatalog.chatModels.any { it.publicModelId == "my-vendor--image-model" })
    }

    @Test fun compatibleEndpointsAcceptBaseWithOrWithoutV1() {
        val withV1 = ApiProviderConfig(name = "A", baseUrl = "https://example.com/v1/", apiKey = "")
        val withoutV1 = ApiProviderConfig(name = "B", baseUrl = "https://example.com", apiKey = "")
        assertEquals("https://example.com/v1/models", OpenAiCompatibleClient.endpoint(withV1, "models"))
        assertEquals("https://example.com/v1/chat/completions", OpenAiCompatibleClient.endpoint(withoutV1, "chat/completions"))
        val fullChatUrl = ApiProviderConfig(name = "C", baseUrl = "https://example.com/v1/chat/completions", apiKey = "")
        val fullModelsUrl = ApiProviderConfig(name = "D", baseUrl = "https://example.com/v1/models", apiKey = "")
        assertEquals("https://example.com/v1/models", OpenAiCompatibleClient.endpoint(fullChatUrl, "models"))
        assertEquals("https://example.com/v1/chat/completions", OpenAiCompatibleClient.endpoint(fullModelsUrl, "chat/completions"))
    }

    @Test fun modelDiscoverySeparatesKnownChatAndImageModels() {
        val result = OpenAiCompatibleClient.parseModelList("""
            {"data":[
              {"id":"gpt-4.1"},
              {"id":"gpt-image-2"},
              {"id":"vendor-art","capabilities":["image_generation"]},
              {"id":"vision-chat"}
            ]}
        """.trimIndent())
        assertEquals(listOf("gpt-4.1", "vision-chat"), result.chatModels)
        assertEquals(listOf("gpt-image-2", "vendor-art"), result.imageModels)
    }

    @Test fun imageInputVisionNameIsNotMistakenForImageGeneration() {
        val result = OpenAiCompatibleClient.parseModelList(
            "{\"data\":[{\"id\":\"qwen-vl-vision\",\"modalities\":[\"text\",\"image\"]}]}"
        )
        assertEquals(listOf("qwen-vl-vision"), result.chatModels)
        assertTrue(result.imageModels.isEmpty())
    }

    @Test fun ratioSelectsClosestSizeAdvertisedByUpstream() {
        val sizes = listOf("1024x1024", "768x1360", "896x1184", "1360x768", "1184x896")
        assertEquals("768x1360", OpenAiCompatibleClient.closestPixelSize("9:16", sizes))
        assertEquals("1360x768", OpenAiCompatibleClient.closestPixelSize("16:9", sizes))
        assertEquals("1024x1024", OpenAiCompatibleClient.closestPixelSize("1:1", sizes))
    }

    @Test fun hiApiDiscoveryRemovesRetiredAndEditOnlyModels() {
        val config = ApiProviderConfig(name = "hiapi", baseUrl = "https://api.hiapi.ai", apiKey = "secret")
        val discovered = ApiTestResult(
            success = true,
            message = "ok",
            chatModels = listOf("gpt-4.1"),
            imageModels = listOf("gpt-image-1.5", "gpt-image-2/image-to-image", "nano-banana-2")
        )
        val normalized = OpenAiCompatibleClient.normalizeProviderModels(config, discovered)
        assertFalse("gpt-image-1.5" in normalized.imageModels)
        assertFalse("gpt-image-2/image-to-image" in normalized.imageModels)
        assertTrue("nano-banana-2" in normalized.imageModels)
        assertTrue(normalized.imageModels.containsAll(HiApiImageClient.textToImageModels))
        assertEquals(20, HiApiImageClient.textToImageModels.size)
        assertTrue(HiApiImageClient.textToImageModels.none { it.contains("/image-to-image") })
    }

    @Test fun hiApiRetiredGptImageModelMapsToCurrentTextToImage() {
        assertEquals("gpt-image-2/text-to-image", HiApiImageClient.canonicalModel("gpt-image-1.5"))
        assertEquals("gpt-image-2/text-to-image@ext", HiApiImageClient.canonicalModel("gpt-image-2/text-to-image@ext"))
        assertEquals("nano-banana-2", HiApiImageClient.canonicalModel("nano-banana-2"))
    }

    @Test fun hiApiPixelSizeMapsToSupportedAspectRatio() {
        assertEquals("9:16", HiApiImageClient.normalizeRatio("1024x1792"))
        assertEquals("1:1", HiApiImageClient.normalizeRatio("1024x1024"))
    }

    @Test fun automaticRouteRanksFastHealthyProviderFirst() {
        val slow = ApiProviderConfig(id = "slow", name = "Slow", baseUrl = "https://slow.test", apiKey = "")
        val fast = ApiProviderConfig(id = "fast", name = "Fast", baseUrl = "https://fast.test", apiKey = "")
        ApiRouteSelector.record(slow.id, true, 800)
        ApiRouteSelector.record(fast.id, true, 120)
        assertEquals("fast", ApiRouteSelector.rank(listOf(slow, fast)).first().id)
    }

    @Test fun failedRouteIsCooledDownBehindHealthyRoute() {
        val failed = ApiProviderConfig(id = "failed", name = "Failed", baseUrl = "https://failed.test", apiKey = "")
        val healthy = ApiProviderConfig(id = "healthy", name = "Healthy", baseUrl = "https://healthy.test", apiKey = "")
        ApiRouteSelector.record(failed.id, false, 50)
        ApiRouteSelector.record(healthy.id, true, 400)
        assertEquals("healthy", ApiRouteSelector.rank(listOf(failed, healthy)).first().id)
    }

    @Test fun promptTruncationPreservesChineseAndEmojiCodePoints() {
        val source = "中文🙂测试ABCDE"
        val shortened = OpenAiCompatibleClient.truncateCodePoints(source, 4)
        assertEquals("中文🙂测", shortened)
        assertEquals(4, shortened.codePointCount(0, shortened.length))
    }
}
