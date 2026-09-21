package com.wenwei.deepseekproxy.workbuddy

import okio.BufferedSource
import org.json.JSONObject

/** WorkBuddy chat 端点的 SSE 流读取器。 */
internal object WorkBuddySse {
    private const val DONE = "[DONE]"

    /**
     * 边读边回调增量文本。
     *
     * @return 通过 [onDelta] 发出的总字符数；0 表示上游没有给出任何文本。
     */
    suspend fun parseStreaming(source: BufferedSource?, onDelta: suspend (String) -> Unit): Int {
        if (source == null) return 0
        var emitted = 0
        val chunk = StringBuilder()
        while (true) {
            val line = runCatching { source.readUtf8Line() }.getOrNull() ?: break
            if (line.isBlank()) {
                emitted += flushChunk(chunk, onDelta)
                continue
            }
            if (!line.startsWith("data:")) continue
            chunk.append(line.substringAfter("data:").trimStart()).append('\n')
        }
        emitted += flushChunk(chunk, onDelta)
        return emitted
    }

    private suspend fun flushChunk(chunk: StringBuilder, onDelta: suspend (String) -> Unit): Int {
        val payload = chunk.toString().trim()
        chunk.setLength(0)
        if (payload.isEmpty() || payload == DONE) return 0
        val text = extractDelta(payload) ?: return 0
        onDelta(text)
        return text.length
    }

    /** 从单个 SSE data 负载中提取增量文本；无法识别时返回 null。 */
    internal fun extractDelta(payload: String): String? {
        val event = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (event.has("error")) return null
        val choice = event.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")
        return delta?.optString("content")?.takeIf { it.isNotEmpty() }
            ?: choice?.optString("text")?.takeIf { it.isNotEmpty() }
            ?: event.optString("content").takeIf { it.isNotEmpty() }
    }
}
