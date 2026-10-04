package com.wenwei.deepseekproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wenwei.deepseekproxy.proxy.LogStore
import com.wenwei.deepseekproxy.proxy.LocalGatewayTokenStore
import com.wenwei.deepseekproxy.proxy.LocalGatewayServiceLifecycle
import com.wenwei.deepseekproxy.proxy.ProxyServer
import com.wenwei.deepseekproxy.proxy.DeepSeekPowWebView
import com.wenwei.deepseekproxy.workbuddy.WorkBuddyCredentialStore

/** Foreground host for a local gateway that the user must start explicitly. */
class ProxyService : android.app.Service() {
    companion object {
        const val CHANNEL_ID = "proxy_foreground"
        const val NOTIF_ID = 1
        const val EXTRA_PORT = "port"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_STREAM = "stream"
        const val ACTION_START_EXPLICIT = "com.wenwei.deepseekproxy.action.START_LOCAL_GATEWAY"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WorkBuddyCredentialStore.attach(this)
        com.wenwei.deepseekproxy.proxy.AdminMigration.attach(this)
        createChannel()
        runCatching {
            assets.open("sha3_wasm_bg.wasm").use { DeepSeekPowWebView.init(this, it.readBytes()) }
        }.onFailure { LogStore.e("DeepSeek", "PoW 初始化失败: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_EXPLICIT) {
            LogStore.w("Proxy", "忽略非用户显式发起的服务启动")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val port = intent.getIntExtra(EXTRA_PORT, 8800)
        val cookiesList = intent.getStringArrayListExtra(EXTRA_COOKIES) ?: arrayListOf()
        val stream = intent.getBooleanExtra(EXTRA_STREAM, true)
        LocalGatewayServiceLifecycle.startRequested()
        try {
            startForeground(NOTIF_ID, buildNotification("服务运行中 · 端口 $port · ${if (stream) "流式" else "非流式"}"))
            ProxyServer.streamMode = stream
            // csrfkey 留空由 CNB 自动获取；cookie 列表用于豆包接口（自动轮换）
            ProxyServer.start(
                port = port,
                csrfkeyProvider = { "" },
                cookieListProvider = { cookiesList },
                storageDir = cacheDir,
                bearerToken = LocalGatewayTokenStore(this).getOrCreate(),
            )
            check(ProxyServer.isRunning()) { "Local gateway did not enter running state" }
            LocalGatewayServiceLifecycle.startSucceeded()
            LogStore.i("Proxy", "▶ 前台服务已启动，端口 $port，流式=$stream")
        } catch (failure: Throwable) {
            ProxyServer.stop()
            LocalGatewayServiceLifecycle.startFailed(failure.message.orEmpty())
            stopForeground(true)
            stopSelf(startId)
            LogStore.e("Proxy", "本地网关启动失败: ${failure.message ?: failure.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ProxyServer.stop()
        LocalGatewayServiceLifecycle.stopped()
        DeepSeekPowWebView.destroy()
        stopForeground(true)
        LogStore.i("Proxy", "■ 前台服务已停止")
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "混元服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "用户显式启动后显示本地网关运行状态"
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("混元")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
