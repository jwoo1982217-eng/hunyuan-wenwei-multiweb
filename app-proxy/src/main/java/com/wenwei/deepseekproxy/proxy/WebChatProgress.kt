package com.wenwei.deepseekproxy.proxy

internal data class WebChatSnapshot(
    val acknowledged: Boolean = false,
    val busy: Boolean = false,
    val finished: Boolean = false,
    val text: String = "",
    val images: List<String> = emptyList(),
    val pageModel: String = "",
)

/** A pause in output is not completion; require a website completion signal too. */
internal class WebChatProgress(private val settleMs: Long = 750) {
    private var last = WebChatSnapshot()
    private var changedAt = 0L
    fun accept(current: WebChatSnapshot, nowMs: Long): Boolean {
        if (current.text != last.text || current.images != last.images ||
            current.finished != last.finished || current.busy != last.busy ||
            current.acknowledged != last.acknowledged) changedAt = nowMs
        last = current
        return current.acknowledged && current.finished && !current.busy &&
            (current.text.isNotBlank() || current.images.isNotEmpty()) && nowMs - changedAt >= settleMs
    }
}

/** An optional website model requirement verifies the page; it never selects a model. */
data class WebChatOptions(
    val requiredModel: String? = null,
    val replyTimeoutMs: Long = 180_000,
) {
    companion object {
        fun fromRequest(model: String?, timeoutSeconds: Int?): WebChatOptions {
            if (timeoutSeconds != null && timeoutSeconds !in 30..3600) {
                throw WebAutomationException("invalid_web_timeout", "web_timeout_seconds 必须在 30 到 3600 之间")
            }
            if (model != null && (model.isBlank() || model.length > 96)) {
                throw WebAutomationException("invalid_web_model", "web_model 必须是 1 到 96 个字符的网页模型名称")
            }
            return WebChatOptions(model?.trim(), (timeoutSeconds ?: 180) * 1000L)
        }
    }
}

internal fun verifyWebChatModel(expected: String?, actual: String): Boolean {
    if (expected.isNullOrBlank()) return false
    val wanted = expected.trim().lowercase()
    val observed = actual.trim().lowercase()
    if (observed.isBlank()) throw WebAutomationException("web_model_unverified",
        "无法识别网页当前模型，请在浏览器页确认模型后重试")
    val matches = if (wanted == "pro") Regex("(^|[^a-z])pro([^a-z]|$)").containsMatchIn(observed)
        else observed == wanted
    if (!matches) throw WebAutomationException("web_model_mismatch",
        "网页当前模型与 web_model 要求不一致，请在浏览器页选择所需模型")
    return true
}

/** SSE cannot replace already published text. Never concatenate a rewritten answer twice. */
internal class WebChatTextDelta {
    private var previous = ""
    fun next(text: String): String {
        if (!text.startsWith(previous)) throw WebAutomationException("web_response_rewritten",
            "网页修改了已返回的正文，本次流式结果未完成；可使用非流式重新调用")
        return text.removePrefix(previous).also { previous = text }
    }
}
