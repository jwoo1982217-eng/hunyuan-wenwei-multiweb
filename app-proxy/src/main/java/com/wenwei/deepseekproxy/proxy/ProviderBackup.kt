package com.wenwei.deepseekproxy.proxy

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 接口配置本地备份（移植自墨🐳 v1.0.13 ProviderBackup.kt）：
 * 导出到 Download/（MediaStore），恢复时走 AdminMigration.importApi 合并导入。
 * 文件含明文密钥，导出提示保管。
 */
object ProviderBackup {
    private const val SCHEMA = AdminMigration.SCHEMA

    /** @return (文件名, 是否成功, 提示信息) */
    fun exportToDownload(context: Context): Triple<String, Boolean, String> = runCatching {
        val exportJson = AdminMigration.exportJson()
        val name = "墨太极接口备份-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
        val values = ContentValues().apply {
            put("_display_name", name)
            put("mime_type", "application/json")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching Triple(name, false, "无法创建下载文件")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(exportJson.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: return@runCatching Triple(name, false, "无法打开输出流")
        Triple(name, true, "已导出（含明文密钥，请妥善保管）")
    }.getOrElse { Triple("", false, "导出失败: ${it.message}") }

    /** @return (接口数, Web数, 错误信息——空串表示可恢复) */
    fun preview(context: Context, uri: Uri): Triple<Int, Int, String> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return@runCatching Triple(0, 0, "无法读取文件")
        val obj = JSONObject(text)
        val schema = obj.optString("schema", "")
        if (schema != SCHEMA && !obj.has("apiProviders") && !obj.has("customWebProviders")) {
            return@runCatching Triple(0, 0, "不是接口备份文件（schema=$schema）")
        }
        Triple(
            obj.optJSONArray("apiProviders")?.length() ?: 0,
            obj.optJSONArray("customWebProviders")?.length() ?: 0,
            ""
        )
    }.getOrElse { Triple(0, 0, "读取失败: ${it.message}") }

    /** 恢复（合并模式；接口 + 自定义 Web 一起导回），返回用户可读结果 */
    fun restore(context: Context, uri: Uri): String = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return@runCatching "无法读取文件"
        val obj = JSONObject(text)
        if (obj.optString("schema", "") == SCHEMA) obj.put("mode", "merge")
        val apiResult = AdminMigration.importApi(obj.toString())
        val webResult = runCatching { AdminMigration.importWeb(obj.toString()) }.getOrNull()
        runCatching {
            val api = JSONObject(apiResult)
            val web = webResult?.let { runCatching { JSONObject(it) }.getOrNull() }
            val imported = api.optInt("imported", 0) + (web?.optInt("imported", 0) ?: 0)
            if (api.optBoolean("ok") == true) "已恢复 $imported 项配置" else apiResult
        }.getOrDefault(apiResult)
    }.getOrElse { "恢复失败: ${it.message}" }
}
