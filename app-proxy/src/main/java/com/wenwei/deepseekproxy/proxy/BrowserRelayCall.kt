package com.wenwei.deepseekproxy.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable
data class BrowserCallRecord(
    val requestId: String,
    val modelId: String,
    val startedAt: Long,
    val stage: String = "排队",
    val state: String = "running",
    val elapsedMs: Long = 0,
    val queueMs: Long? = null,
    val pageReadyMs: Long? = null,
    val submissionMs: Long? = null,
    val firstOutputMs: Long? = null,
    val pageModel: String = "",
    val modelSelectionChecked: Boolean = false,
    val errorCode: String = "",
)

/** Bounded diagnostics, containing no prompts, replies, cookies or credentials. */
object BrowserCallTelemetry {
    private val records = MutableStateFlow<List<BrowserCallRecord>>(emptyList())
    val calls = records.asStateFlow()
    @Volatile var hostState: String = "closed"

    @Synchronized internal fun publish(record: BrowserCallRecord) {
        records.value = (listOf(record) + records.value.filterNot { it.requestId == record.requestId }).take(8)
    }
}

internal class BrowserRelayCall(modelId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BrowserRelayCall>
    private val started = System.nanoTime()
    var record = BrowserCallRecord(UUID.randomUUID().toString(), modelId, System.currentTimeMillis())
        private set
    val id: String get() = record.requestId
    private fun elapsed() = (System.nanoTime() - started) / 1_000_000

    fun phase(stage: String) {
        record = record.copy(stage = stage, elapsedMs = elapsed())
        publish()
        LogStore.i("WebCall", "${record.modelId} ${id.take(8)} · $stage · ${record.elapsedMs}ms")
    }
    fun acquired() { record = record.copy(queueMs = elapsed()); phase("等待网页") }
    fun ready() {
        record = record.copy(pageReadyMs = elapsed()); phase("填写并校验输入")
    }
    fun received() {
        if (record.submissionMs != null) return
        record = record.copy(submissionMs = elapsed()); phase("等待回答")
    }
    fun output() {
        if (record.firstOutputMs != null) return
        record = record.copy(firstOutputMs = elapsed()); phase("读取回答")
    }
    fun model(label: String, checked: Boolean) {
        record = record.copy(pageModel = label.take(96), modelSelectionChecked = checked)
        publish()
        LogStore.i("WebCall", "${record.modelId} ${id.take(8)} · 网页模型=${label.ifBlank { "未识别" }} · 已校验=$checked")
    }
    fun finish(state: String, code: String = "") {
        record = record.copy(state = state, errorCode = code, elapsedMs = elapsed(),
            stage = when (state) { "complete" -> "已完成"; "cancelled" -> "已取消"; else -> "调用失败" })
        publish()
        LogStore.i("WebCall", "${record.modelId} ${id.take(8)} · ${record.stage} · " +
            "总计=${record.elapsedMs}ms 排队=${record.queueMs}ms 网页就绪=${record.pageReadyMs}ms " +
            "接收=${record.submissionMs}ms 首次输出=${record.firstOutputMs ?: "未收到"}ms" +
            code.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty())
    }
    private fun publish() = BrowserCallTelemetry.publish(record.copy(elapsedMs = elapsed()))
}

/** All DOM adapters share one real browser, including chat, images and video. */
internal class BrowserRelayQueue(private val queueTimeoutMs: Long = 30_000) {
    private val lane = Mutex()
    suspend fun <T> run(modelId: String, block: suspend () -> T): T {
        val call = BrowserRelayCall(modelId)
        var acquired = false
        val deadline = System.nanoTime() + queueTimeoutMs * 1_000_000
        try {
            call.phase("排队")
            while (!acquired) {
                currentCoroutineContext().ensureActive()
                acquired = lane.tryLock()
                if (!acquired) {
                    if (System.nanoTime() >= deadline) throw WebAutomationException(
                        "browser_busy", "另一个网页任务仍在执行，请等待完成或取消后再试")
                    delay(50)
                }
            }
            call.acquired()
            return withContext(call) { block() }.also { call.finish("complete") }
        } catch (error: Throwable) {
            call.finish(if (error is CancellationException) "cancelled" else "failed",
                (error as? WebAutomationException)?.code ?: error.javaClass.simpleName)
            throw error
        } finally {
            if (acquired) lane.unlock()
        }
    }
}

internal object BrowserRelayLane {
    private val queue = BrowserRelayQueue()
    suspend fun <T> run(modelId: String, block: suspend () -> T): T = queue.run(modelId, block)
}
