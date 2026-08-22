package com.wenwei.deepseekproxy.proxy

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Bridges automated WebView uploads to the real onShowFileChooser callback. */
object NativeWebFileChooserBridge {
    private data class PendingFile(val name: String, val mime: String, val bytes: ByteArray)

    @Volatile
    private var pending: List<PendingFile> = emptyList()

    @Volatile
    private var consumed = false

    fun prepare(attachments: List<ChatAttachment>): Boolean {
        android.util.Log.i("NativeWeb", "prepare attachments=${attachments.size}")
        val files = attachments.mapNotNull { item ->
            val encoded = item.dataUrl.substringAfter("base64,", "")
            runCatching {
                PendingFile(item.name.ifBlank { "attachment-${UUID.randomUUID()}.bin" }, item.mime, Base64.decode(encoded, Base64.DEFAULT))
            }.getOrNull()
        }
        pending = files
        consumed = false
        return files.isNotEmpty()
    }

    fun consume(context: Context, callback: android.webkit.ValueCallback<Array<Uri>>): Boolean {
        android.util.Log.i("NativeWeb", "consume pending=${pending.size}")
        val files = pending
        if (files.isEmpty()) return false
        val dir = File(context.cacheDir, "web-upload").apply { mkdirs() }
        val uris = files.map { item ->
            val safeName = item.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(dir, "${UUID.randomUUID()}-$safeName")
            file.outputStream().use { it.write(item.bytes) }
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.toTypedArray()
        uris.forEach { uri ->
            context.grantUriPermission("com.android.chrome", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pending = emptyList()
        consumed = true
        callback.onReceiveValue(uris)
        return true
    }

    fun wasConsumed(): Boolean = consumed

    fun clear() {
        pending = emptyList()
        consumed = false
    }
}
