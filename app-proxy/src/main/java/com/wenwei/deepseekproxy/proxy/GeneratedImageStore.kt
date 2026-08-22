package com.wenwei.deepseekproxy.proxy

import android.graphics.Bitmap
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** App-private cache exposed only through the local gateway image route. */
object GeneratedImageStore {
    @Volatile private var directory: File? = null
    @Volatile private var port: Int = 8800

    fun configure(storageDir: File, serverPort: Int) {
        directory = File(storageDir, "generated_images").apply { mkdirs() }
        port = serverPort
    }

    fun saveDataUrl(dataUrl: String): String {
        val comma = dataUrl.indexOf(',')
        require(dataUrl.startsWith("data:image/") && comma > 0) { "Gemini 图片数据格式无效" }
        val mime = dataUrl.substringAfter("data:").substringBefore(';').lowercase()
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            else -> if (mime.startsWith("video/")) "mp4" else "png"
        }
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        require(bytes.isNotEmpty()) { "Gemini 图片数据为空" }
        val file = File(requireNotNull(directory) { "图片缓存未初始化" }, "${UUID.randomUUID()}.$extension")
        file.writeBytes(bytes)
        return "http://127.0.0.1:$port/v1/generated-images/${file.name}"
    }

    fun saveBitmap(bitmap: Bitmap): String {
        require(bitmap.width > 0 && bitmap.height > 0) { "Gemini 图片尺寸无效" }
        val file = File(requireNotNull(directory) { "图片缓存未初始化" }, "${UUID.randomUUID()}.png")
        FileOutputStream(file).use { output ->
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Gemini 图片保存失败" }
        }
        return "http://127.0.0.1:$port/v1/generated-images/${file.name}"
    }

    fun saveBytes(bytes: ByteArray, contentType: String?): String {
        require(bytes.isNotEmpty()) { "网页图片数据为空" }
        val mime = contentType.orEmpty().substringBefore(';').lowercase()
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            else -> if (mime.startsWith("video/")) "mp4" else "png"
        }
        val file = File(requireNotNull(directory) { "图片缓存未初始化" }, "${UUID.randomUUID()}.$extension")
        file.writeBytes(bytes)
        return "http://127.0.0.1:$port/v1/generated-images/${file.name}"
    }

    fun read(id: String): Pair<ByteArray, String>? {
        if (!id.matches(Regex("[A-Za-z0-9._-]+"))) return null
        val root = directory ?: return null
        val file = File(root, id)
        if (!file.isFile || file.parentFile?.canonicalFile != root.canonicalFile) return null
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
        return file.readBytes() to mime
    }
}
