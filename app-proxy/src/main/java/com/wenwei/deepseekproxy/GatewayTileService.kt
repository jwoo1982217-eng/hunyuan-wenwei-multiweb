package com.wenwei.deepseekproxy

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.wenwei.deepseekproxy.proxy.LogStore
import com.wenwei.deepseekproxy.proxy.ProxyServer

/**
 * 系统下拉快捷开关（Quick Settings Tile）：一键启停本地网关。
 * 移植自墨🐳 v1.0.13（GatewayTileService.kt）。
 */
class GatewayTileService : TileService() {

    private fun refreshTile() {
        val tile = qsTile ?: return
        val running = ProxyServer.isRunning()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (running) "网关运行中" else "点击启动网关"
        }
        tile.updateTile()
    }

    private fun startGateway() {
        val prefs = getSharedPreferences("ds_proxy", MODE_PRIVATE)
        // 与应用内启动一致的默认参数；cookie 列表留给豆包轮换（无则空表）
        val cookies = runCatching {
            prefs.getString("doubao_cookies", null)?.let { raw ->
                val array = org.json.JSONArray(raw)
                (0 until array.length()).map { array.optString(it, "") }
            } ?: listOfNotNull(
                prefs.getString("doubao_cookie", null) ?: prefs.getString("token", null)
            )
        }.getOrDefault(emptyList())
        val intent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START_EXPLICIT
            putExtra("port", prefs.getInt("port", 8800))
            putExtra("cookies", ArrayList(cookies))
            putExtra("stream", prefs.getBoolean("stream_mode", true))
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
            LogStore.i("Proxy", "快捷开关：已请求启动本地网关")
        }.onFailure { e ->
            LogStore.w("Proxy", "快捷开关直启失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onClick() {
        super.onClick()
        if (ProxyServer.isRunning()) {
            stopService(Intent(this, ProxyService::class.java))
            LogStore.i("Proxy", "快捷开关：已请求停止本地网关")
        } else {
            startGateway()
        }
        refreshTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }
}
