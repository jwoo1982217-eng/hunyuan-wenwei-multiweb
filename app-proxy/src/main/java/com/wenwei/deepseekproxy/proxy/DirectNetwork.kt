package com.wenwei.deepseekproxy.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * 国内线路自动绕过 VPN（2026-10-04）：
 * VPN（tun0）会把全机流量导到隧道出口，而百炼/AutoClaw 这些国内上游按出口 IP 记额度——
 * 共享 VPN 出口的额度早被刷光，还会被风控区别对待（AutoClaw 海外 IP 签发的 token 直接拒收）。
 *
 * Android 允许 App 把自己的进程/套接字绑定到物理网络（WiFi/蜂窝），从而绕开 VPN 隧道：
 *  - apply(builder)：给 OkHttp 客户端挂物理网的 socketFactory + DNS（按请求级绕过，进程其余流量不受影响）
 *  - bindProcess(true/false)：进程级绑定（给 WebView 用——它没法按请求改 socketFactory）
 *
 * 物理网不可用（如 VPN 把底层网全收走）时全部回落默认行为，不影响可用性。
 */
object DirectNetwork {
    @Volatile private var appContext: Context? = null
    @Volatile private var cached: Pair<Network, Long>? = null
    private const val CACHE_MS = 30_000L

    /** 在 ProxyService.onCreate / MainActivity.onCreate 都调一次，确保任何入口都有 context */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun cm(): ConnectivityManager? =
        appContext?.getSystemService(ConnectivityManager::class.java)

    /** 找一个可用的非 VPN 物理 网络（优先已验证的），30 秒缓存 */
    fun find(): Network? {
        cached?.let { (n, t) ->
            if (System.currentTimeMillis() - t < CACHE_MS) return n
        }
        val manager = cm() ?: return null
        val best = manager.allNetworks
            .mapNotNull { n ->
                val caps = manager.getNetworkCapabilities(n) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                n to (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0)
            }
            .maxByOrNull { it.second }?.first
        cached = best?.let { it to System.currentTimeMillis() }
        return best
    }

    /** OkHttp 客户端挂直连（物理网 socketFactory + 该网的 DNS）。物理网不可用则原样返回。 */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val network = find() ?: return builder
        return builder
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(host: String): List<InetAddress> =
                    network.getAllByName(host).toList()
            })
    }

    /** 进程级绑定（WebView 用）。direct=true 绑物理网，false 恢复系统默认（有 VPN 走 VPN）。 */
    fun bindProcess(direct: Boolean) {
        val manager = cm() ?: return
        runCatching {
            manager.bindProcessToNetwork(if (direct) find() else null)
        }
    }
}
