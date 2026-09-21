package com.wenwei.deepseekproxy.workbuddy

import android.content.Context
import com.wenwei.deepseekproxy.proxy.SessionSecretCipher
import org.json.JSONObject

/**
 * 持久化 WorkBuddy 插件授权凭证（每个区域一份）。
 *
 * 这是官方插件授权流发放的 token，不是抓取的浏览器 cookie：
 * 由 WorkBuddy 自己的插件授权接口签发，客户端负责保存与轮换。
 * 落盘前经 Android Keystore AES-GCM 加密（与 WebSessionStore 同一套 Cipher）。
 */
object WorkBuddyCredentialStore {
    private const val PREFS = "workbuddy_credential"

    @Volatile private var appContext: Context? = null
    private val cipher by lazy { SessionSecretCipher() }
    private val refreshLock = Any()

    /** 在主 Activity / 前台服务启动时挂载 Application context，供无上下文的调用方使用。 */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun save(region: WorkBuddyClient.Region, credential: WorkBuddyClient.Credential) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        if (!credential.usable) {
            prefs.edit().remove(key(region)).apply()
            return
        }
        val raw = WorkBuddyClient.toJson(credential).toString()
        prefs.edit().putString(key(region), cipher.encrypt(raw)).apply()
    }

    fun load(region: WorkBuddyClient.Region): WorkBuddyClient.Credential? {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        val raw = prefs.getString(key(region), null) ?: return null
        val json = runCatching { JSONObject(cipher.decrypt(raw)) }.getOrNull() ?: return null
        return WorkBuddyClient.fromGrant(json).takeIf { it.usable }
    }

    fun clear(region: WorkBuddyClient.Region) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(key(region))?.apply()
    }

    fun isLoggedIn(region: WorkBuddyClient.Region): Boolean = load(region) != null

    /**
     * 返回当前可用凭证：临期且持有 refreshToken 时先无感续期并落盘。
     * 续期失败时返回旧凭证，由调用方在 401 后再走 [refreshCredential] 重试。
     */
    fun freshCredential(region: WorkBuddyClient.Region): WorkBuddyClient.Credential? {
        val current = load(region) ?: return null
        if (!current.expiringSoon() || current.refreshToken.isBlank()) return current
        return refreshCredential(region) ?: current
    }

    /** 强制刷新凭证并落盘；刷新失败（含未登录）返回 null。并发调用只会放行一次刷新。 */
    fun refreshCredential(region: WorkBuddyClient.Region): WorkBuddyClient.Credential? =
        synchronized(refreshLock) {
            val current = load(region) ?: return@synchronized null
            // 另一个线程可能刚刚刷新过：若落盘凭证已不再临期，直接复用。
            if (!current.expiringSoon()) return@synchronized current
            runCatching { WorkBuddyClient.refresh(region, current) }
                .onSuccess { save(region, it) }
                .getOrNull()
        }

    private fun key(region: WorkBuddyClient.Region): String = "credential_" + region.name
}
