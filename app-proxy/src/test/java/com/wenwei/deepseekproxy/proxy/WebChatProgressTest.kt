package com.wenwei.deepseekproxy.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class WebChatProgressTest {
    @Test fun pauseWhileGeneratingDoesNotComplete() {
        val progress = WebChatProgress()
        val partial = WebChatSnapshot(acknowledged = true, busy = true, text = "半句话")
        assertFalse(progress.accept(partial, 0))
        assertFalse(progress.accept(partial, 20_000))
        assertFalse(progress.accept(partial.copy(busy = false), 40_000))
        assertFalse(progress.accept(partial.copy(busy = false), 60_000))
    }

    @Test fun completionNeedsReceiptAndSettledFinalOutput() {
        val progress = WebChatProgress()
        val end = WebChatSnapshot(finished = true, text = "pong")
        assertFalse(progress.accept(end, 0))
        assertFalse(progress.accept(end, 1000))
        assertFalse(progress.accept(end.copy(acknowledged = true), 2000))
        assertFalse(progress.accept(end.copy(acknowledged = true), 2700))
        assertTrue(progress.accept(end.copy(acknowledged = true), 2750))
    }

    @Test fun finalRevisionRestartsCompletionWindow() {
        val progress = WebChatProgress()
        val end = WebChatSnapshot(acknowledged = true, finished = true, text = "短答")
        assertFalse(progress.accept(end, 0))
        assertFalse(progress.accept(end.copy(text = "完整答案"), 700))
        assertFalse(progress.accept(end.copy(text = "完整答案"), 1400))
        assertTrue(progress.accept(end.copy(text = "完整答案"), 1450))
    }

    @Test fun imageOnlyGeminiReplyCanComplete() {
        val progress = WebChatProgress()
        val image = WebChatSnapshot(acknowledged = true, finished = true, images = listOf("blob:generated"))
        assertFalse(progress.accept(image, 0))
        assertTrue(progress.accept(image, 750))
        assertFalse(WebChatProgress().accept(image.copy(images = emptyList()), 10000))
    }

    @Test fun streamingNeverDuplicatesRewrittenText() {
        val delta = WebChatTextDelta()
        assertEquals("你好", delta.next("你好"))
        assertEquals("", delta.next("你好"))
        assertEquals("世界", delta.next("你好世界"))
        assertCode("web_response_rewritten") { delta.next("您好世界") }
    }

    @Test fun proRequirementChecksObservedPageRatherThanPublicAlias() {
        assertFalse(verifyWebChatModel(null, "未识别"))
        assertTrue(verifyWebChatModel("Pro", "GPT-6 Pro"))
        assertTrue(verifyWebChatModel("Gemini 2.5 Pro", "gemini 2.5 pro"))
        assertCode("web_model_unverified") { verifyWebChatModel("Pro", "") }
        assertCode("web_model_mismatch") { verifyWebChatModel("Pro", "Professional") }
        assertCode("web_model_mismatch") { verifyWebChatModel("Pro", "GPT-6 Thinking") }
    }

    @Test fun explicitLongReplyTimeoutHasBounds() {
        assertEquals(180_000L, WebChatOptions.fromRequest(null, null).replyTimeoutMs)
        assertEquals(3_600_000L, WebChatOptions.fromRequest("Pro", 3600).replyTimeoutMs)
        assertCode("invalid_web_timeout") { WebChatOptions.fromRequest(null, 29) }
        assertCode("invalid_web_timeout") { WebChatOptions.fromRequest(null, 3601) }
        assertCode("invalid_web_model") { WebChatOptions.fromRequest(" ", null) }
    }

    @Test fun streamFailureIsAnExplicitJsonError() {
        val event = Json.parseToJsonElement(webChatErrorJson(
            WebAutomationException("submission_unconfirmed", "未确认\n接收"),
        )).jsonObject.getValue("error").jsonObject
        assertEquals("submission_unconfirmed", event.getValue("code").jsonPrimitive.content)
        assertEquals("未确认\n接收", event.getValue("message").jsonPrimitive.content)
        assertFalse(event.containsKey("finish_reason"))
    }

    @Test fun differentProvidersCannotNavigateTheBrowserTogether() = runBlocking {
        withTimeout(2000) {
            val queue = BrowserRelayQueue()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = launch { queue.run("chatgpt-web") { entered.complete(Unit); release.await() } }
            entered.await()
            val second = launch { queue.run("gemini-web") { secondEntered.complete(Unit) } }
            kotlinx.coroutines.yield()
            assertFalse(secondEntered.isCompleted)
            release.complete(Unit)
            first.join(); second.join()
            assertTrue(secondEntered.isCompleted)
        }
    }

    @Test fun cancelledCallReleasesBrowserForNextCall() = runBlocking {
        withTimeout(2000) {
            val queue = BrowserRelayQueue()
            val entered = CompletableDeferred<Unit>()
            val first = launch { queue.run("chatgpt-web") { entered.complete(Unit); awaitCancellation() } }
            entered.await(); first.cancelAndJoin()
            assertEquals("next", queue.run("gemini-web") { "next" })
        }
    }

    @Test fun occupiedBrowserFailsWithoutSendingSecondTask() = runBlocking {
        withTimeout(2000) {
            val queue = BrowserRelayQueue(queueTimeoutMs = 20)
            val entered = CompletableDeferred<Unit>()
            val first = launch { queue.run("chatgpt-web") { entered.complete(Unit); awaitCancellation() } }
            entered.await()
            try {
                queue.run("gemini-web") { fail("busy task must not send") }
                fail("busy browser must fail")
            } catch (error: WebAutomationException) {
                assertEquals("browser_busy", error.code)
            } finally { first.cancelAndJoin() }
        }
    }

    @Test fun stageExpiryAndCallerCancellationRemainDistinct() = runBlocking {
        try {
            webStageTimeout(10, "chat_timeout", "等待超时") { awaitCancellation() }
            fail("stage must expire")
        } catch (error: WebAutomationException) { assertEquals("chat_timeout", error.code) }
        val started = CompletableDeferred<Unit>()
        val task = async { webStageTimeout(2000, "chat_timeout", "等待超时") { started.complete(Unit); awaitCancellation() } }
        started.await(); task.cancel()
        try { task.await(); fail("caller cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    private fun assertCode(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") }
        catch (error: WebAutomationException) { assertEquals(code, error.code) }
    }
}
