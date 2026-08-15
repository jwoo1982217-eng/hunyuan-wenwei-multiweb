package com.github.jing332.deepseekproxy.proxy

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGatewayContractTest {
    @After
    fun resetProcessState() {
        GatewayModelHealth.clear()
        LocalGatewayServiceLifecycle.stopped()
    }

    @Test
    fun generatedTokenIsRandom256BitHexAndBearerIsRequired() {
        val first = LocalGatewaySecurity.generateToken()
        val second = LocalGatewaySecurity.generateToken()

        assertEquals(64, first.length)
        assertTrue(first.all { it.isDigit() || it in 'a'..'f' })
        assertNotEquals(first, second)
        assertTrue(LocalGatewaySecurity.accepts("Bearer $first", first))
        assertTrue(LocalGatewaySecurity.accepts("bearer $first", first))
        assertFalse(LocalGatewaySecurity.accepts(null, first))
        assertFalse(LocalGatewaySecurity.accepts("Bearer", first))
        assertFalse(LocalGatewaySecurity.accepts("Basic $first", first))
        assertFalse(LocalGatewaySecurity.accepts("Bearer $second", first))
        assertFalse(LocalGatewaySecurity.accepts("Bearer $first extra", first))
        assertFalse(LocalGatewaySecurity.accepts("Bearer $first", ""))
    }

    @Test
    fun imageBearerNeverLeavesExactGatewayImageEndpoint() {
        assertTrue(LocalGatewaySecurity.shouldAuthorizeGeneratedImage(
            "http://127.0.0.1:8800/v1/generated-images/image_1.png", 8800
        ))
        assertTrue(LocalGatewaySecurity.shouldAuthorizeGeneratedImage(
            "http://localhost:8800/v1/generated-images/image-2.webp", 8800
        ))
        assertTrue(LocalGatewaySecurity.shouldAuthorizeGeneratedImage(
            "http://[::1]:8800/v1/generated-images/image.jpg", 8800
        ))
        assertFalse(LocalGatewaySecurity.shouldAuthorizeGeneratedImage(
            "http://127.0.0.1:9900/v1/generated-images/image.png", 8800
        ))
        assertFalse(LocalGatewaySecurity.shouldAuthorizeGeneratedImage(
            "https://127.0.0.1:8800/v1/generated-images/image.png", 8800
        ))
        assertFalse(LocalGatewaySecurity.shouldAuthorizeGeneratedImage(
            "http://127.0.0.1:8800/v1/status", 8800
        ))
        assertFalse(LocalGatewaySecurity.shouldAuthorizeGeneratedImage(
            "http://127.0.0.1.evil:8800/v1/generated-images/image.png", 8800
        ))
        assertFalse(LocalGatewaySecurity.shouldAuthorizeGeneratedImage(
            "http://127.0.0.1:8800/v1/generated-images/../status", 8800
        ))
        assertFalse(LocalGatewaySecurity.shouldAuthorizeGeneratedImage(
            "http://127.0.0.1:8800/v1/generated-images/a%2Fb.png", 8800
        ))
    }

    @Test
    fun customDiscoveryUrlPostsAreClassifiedByOpenAiRequestShape() {
        assertEquals(
            CompatibilityProxyRequestKind.CHAT,
            classifyCompatibilityProxyRequest(
                """{"model":"yuanbao-tencent-com-web","messages":[{"role":"user","content":"ping"}]}"""
            ),
        )
        assertEquals(
            CompatibilityProxyRequestKind.IMAGE,
            classifyCompatibilityProxyRequest(
                """{"model":"yuanbao-tencent-com-image-web","prompt":"blue circle","n":1}"""
            ),
        )
        assertEquals(
            CompatibilityProxyRequestKind.UNKNOWN,
            classifyCompatibilityProxyRequest("""{"model":"yuanbao-tencent-com-web"}"""),
        )
        assertEquals(
            CompatibilityProxyRequestKind.UNKNOWN,
            classifyCompatibilityProxyRequest("not-json"),
        )
    }

    @Test
    fun routeRegistrationAloneNeverMakesAModelReady() {
        val zhipu = model("zhipu-web", WebProviderType.ZHIPU_GUEST)
        val untested = GatewayRuntimeSnapshot()
        assertEquals(
            GatewayReadiness.UNTESTED,
            LocalGatewayStatusResolver.modelStatus(zhipu, untested).readiness,
        )
        assertTrue(LocalGatewayStatusResolver.readyModels(listOf(zhipu), untested).isEmpty())

        val success = untested.copy(modelHealth = mapOf(zhipu.publicModelId to true))
        assertEquals(
            GatewayReadiness.READY,
            LocalGatewayStatusResolver.modelStatus(zhipu, success).readiness,
        )
        assertEquals(listOf(zhipu), LocalGatewayStatusResolver.readyModels(listOf(zhipu), success))

        val failure = untested.copy(modelHealth = mapOf(zhipu.publicModelId to false))
        assertEquals(
            GatewayReadiness.UNAVAILABLE,
            LocalGatewayStatusResolver.modelStatus(zhipu, failure).readiness,
        )
    }

    @Test
    fun modelDiscoveryHealthCannotPromotePublicModelReadiness() {
        val apiModel = ProviderModel(
            publicModelId = "api--text-model",
            displayName = "Text model",
            providerType = WebProviderType.OPENAI_COMPATIBLE,
            capabilities = setOf(ProviderCapability.CHAT),
            accountId = "discovery-provider",
        )
        ApiRouteSelector.record("discovery-provider", success = true, latencyMs = 10)

        val invocationHealth = GatewayModelHealth.snapshot(listOf(apiModel.publicModelId))
        assertEquals(null, invocationHealth[apiModel.publicModelId])
        assertEquals(
            GatewayReadiness.UNTESTED,
            LocalGatewayStatusResolver.modelStatus(
                apiModel,
                GatewayRuntimeSnapshot(modelHealth = invocationHealth),
            ).readiness,
        )

        GatewayModelHealth.record(apiModel.publicModelId, true)
        assertEquals(
            GatewayReadiness.READY,
            LocalGatewayStatusResolver.modelStatus(
                apiModel,
                GatewayRuntimeSnapshot(
                    modelHealth = GatewayModelHealth.snapshot(listOf(apiModel.publicModelId)),
                ),
            ).readiness,
        )
    }

    @Test
    fun completedInvocationPromotesHealthAndExceptionClearsStaleReady() = runBlocking {
        val modelId = "doubao"
        GatewayModelHealth.record(modelId, true)

        val failure = runCatching {
            GatewayModelHealth.recordInvocation(modelId) {
                throw IllegalStateException("stream interrupted before DONE")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(false, GatewayModelHealth.status(modelId))

        val result = GatewayModelHealth.recordInvocation(modelId) { "DONE" }
        assertEquals("DONE", result)
        assertEquals(true, GatewayModelHealth.status(modelId))
    }

    @Test
    fun serviceRunningStateChangesOnlyOnLifecycleConfirmation() {
        LocalGatewayServiceLifecycle.stopped()
        LocalGatewayServiceLifecycle.startRequested()
        assertFalse(LocalGatewayServiceLifecycle.running.value)

        LocalGatewayServiceLifecycle.startSucceeded()
        assertTrue(LocalGatewayServiceLifecycle.running.value)

        LocalGatewayServiceLifecycle.startFailed("bind failed")
        assertFalse(LocalGatewayServiceLifecycle.running.value)
        assertEquals("bind failed", LocalGatewayServiceLifecycle.lastError.value)

        LocalGatewayServiceLifecycle.stopped()
        assertFalse(LocalGatewayServiceLifecycle.running.value)
    }

    @Test
    fun liveLoginAndWebViewPrerequisitesOverrideOldSuccess() {
        val doubao = model("doubao", WebProviderType.DOUBAO_WEB)
        val chatGpt = model("chatgpt-web", WebProviderType.CHATGPT_WEB)
        val success = mapOf(doubao.publicModelId to true, chatGpt.publicModelId to true)

        assertEquals(
            GatewayReadiness.LOGIN_REQUIRED,
            LocalGatewayStatusResolver.modelStatus(
                doubao,
                GatewayRuntimeSnapshot(modelHealth = success),
            ).readiness,
        )
        assertEquals(
            GatewayReadiness.WEBVIEW_REQUIRED,
            LocalGatewayStatusResolver.modelStatus(
                chatGpt,
                GatewayRuntimeSnapshot(
                    authenticatedSessionProviders = setOf(WebProviderType.CHATGPT_WEB),
                    modelHealth = success,
                ),
            ).readiness,
        )
        assertEquals(
            GatewayReadiness.READY,
            LocalGatewayStatusResolver.modelStatus(
                chatGpt,
                GatewayRuntimeSnapshot(
                    authenticatedSessionProviders = setOf(WebProviderType.CHATGPT_WEB),
                    attachedChatProviderId = WebProviderType.CHATGPT_WEB.name,
                    modelHealth = success,
                ),
            ).readiness,
        )
    }

    @Test
    fun arenaGuestRequiresOnlyItsOpenWebViewNotLoginState() {
        val arena = model("arena-guest-web", WebProviderType.ARENA_GUEST)
        val invoked = mapOf(arena.publicModelId to true)
        assertEquals(
            GatewayReadiness.WEBVIEW_REQUIRED,
            LocalGatewayStatusResolver.modelStatus(
                arena,
                GatewayRuntimeSnapshot(modelHealth = invoked),
            ).readiness,
        )
        assertEquals(
            GatewayReadiness.READY,
            LocalGatewayStatusResolver.modelStatus(
                arena,
                GatewayRuntimeSnapshot(
                    attachedChatProviderId = "builtin:ARENA_GUEST",
                    modelHealth = invoked,
                ),
            ).readiness,
        )
    }

    @Test
    fun statusSeparatesRunningServiceFromProviderCapabilityReadiness() {
        val chat = model("zhipu-web", WebProviderType.ZHIPU_GUEST)
        val image = ProviderModel(
            publicModelId = "zhipu-image-web",
            displayName = "Image",
            providerType = WebProviderType.ZHIPU_GUEST,
            capabilities = setOf(ProviderCapability.IMAGE_GENERATION),
        )
        val status = LocalGatewayStatusResolver.build(
            models = listOf(chat, image),
            snapshot = GatewayRuntimeSnapshot(
                modelHealth = mapOf(chat.publicModelId to true, image.publicModelId to false),
            ),
            bindHost = ProxyServer.BIND_HOST,
            port = 8800,
        )

        assertEquals(GatewayReadiness.READY, status.serviceStatus)
        assertEquals("127.0.0.1", status.bindHost)
        assertTrue(status.authRequired)
        assertEquals(
            GatewayReadiness.READY,
            status.capabilities.single { it.name == ProviderCapability.CHAT.name }.readiness,
        )
        assertEquals(
            GatewayReadiness.UNAVAILABLE,
            status.capabilities.single { it.name == ProviderCapability.IMAGE_GENERATION.name }.readiness,
        )
    }

    private fun model(id: String, provider: WebProviderType): ProviderModel = ProviderModel(
        publicModelId = id,
        displayName = id,
        providerType = provider,
        capabilities = setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
    )
}
