package com.wenwei.deepseekproxy.proxy

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedWebSession(
    val id: String,
    val providerType: WebProviderType,
    val displayName: String,
    val cookieHeader: String,
    val authToken: String = "",
    val cookieHeadersByOrigin: Map<String, String> = emptyMap(),
    val modelIds: List<String> = emptyList(),
    val capturedAt: Long
)

/** Provider-scoped browser session snapshots. Cookie values are never written to logs. */
class WebSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("web_provider_sessions", Context.MODE_PRIVATE)
    private val cipher = SessionSecretCipher()

    fun load(): List<SavedWebSession> = decode(prefs.getString(KEY, null)).mapNotNull { session ->
        runCatching {
            session.copy(
                cookieHeader = cipher.decrypt(session.cookieHeader),
                authToken = session.authToken.takeIf { it.isNotBlank() }?.let(cipher::decrypt).orEmpty(),
                cookieHeadersByOrigin = session.cookieHeadersByOrigin.mapValues { (_, value) ->
                    cipher.decrypt(value)
                },
            )
        }.getOrNull()
    }

    fun save(
        providerType: WebProviderType,
        displayName: String,
        cookieHeader: String,
        authToken: String = "",
        cookieHeadersByOrigin: Map<String, String> = emptyMap(),
    ): SavedWebSession {
        require(providerType.supportsWebLogin) { "Provider does not support Web login" }
        require(cookieHeader.isNotBlank() || authToken.isNotBlank()) { "Cookie and authorization are empty" }
        val sessions = load().toMutableList()
        val session = SavedWebSession(
            id = UUID.randomUUID().toString(),
            providerType = providerType,
            displayName = displayName.ifBlank { "${providerType.displayName} ${sessions.count { it.providerType == providerType } + 1}" },
            cookieHeader = cookieHeader,
            authToken = authToken,
            cookieHeadersByOrigin = cookieHeadersByOrigin.filterValues(String::isNotBlank),
            capturedAt = System.currentTimeMillis()
        )
        sessions += session
        persist(sessions)
        return session
    }

    fun delete(id: String) = persist(load().filterNot { it.id == id })

    fun updateModels(id: String, modelIds: List<String>) {
        persist(load().map { if (it.id == id) it.copy(modelIds = modelIds.distinct()) else it })
    }

    private fun persist(sessions: List<SavedWebSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(JSONObject().apply {
                put("id", session.id)
                put("providerType", session.providerType.name)
                put("displayName", session.displayName)
                put("cookieHeader", cipher.encrypt(session.cookieHeader))
                put("authToken", session.authToken.takeIf { it.isNotBlank() }?.let(cipher::encrypt).orEmpty())
                put("cookieHeadersByOrigin", JSONObject().apply {
                    session.cookieHeadersByOrigin.forEach { (origin, header) ->
                        put(origin, cipher.encrypt(header))
                    }
                })
                put("modelIds", JSONArray(session.modelIds))
                put("capturedAt", session.capturedAt)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "sessions_v1"

        internal fun decode(raw: String?): List<SavedWebSession> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val provider = runCatching {
                        WebProviderType.valueOf(obj.getString("providerType"))
                    }.getOrNull() ?: return@mapNotNull null
                    val cookie = obj.optString("cookieHeader")
                    val authToken = obj.optString("authToken")
                    if (cookie.isBlank() && authToken.isBlank()) return@mapNotNull null
                    SavedWebSession(
                        id = obj.optString("id"),
                        providerType = provider,
                        displayName = obj.optString("displayName", provider.displayName),
                        cookieHeader = cookie,
                        authToken = authToken,
                        cookieHeadersByOrigin = obj.optJSONObject("cookieHeadersByOrigin")?.let { origins ->
                            origins.keys().asSequence().associateWith { origin ->
                                origins.optString(origin)
                            }.filterValues(String::isNotBlank)
                        }.orEmpty(),
                        modelIds = obj.optJSONArray("modelIds")?.let { models ->
                            (0 until models.length()).mapNotNull { models.optString(it).takeIf(String::isNotBlank) }
                        }.orEmpty(),
                        capturedAt = obj.optLong("capturedAt")
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}

/** AES-GCM with a non-exportable Android Keystore key. */
internal class SessionSecretCipher {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { "Encrypted Web sessions require Android 6.0+" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    fun encrypt(value: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return value
        val engine = Cipher.getInstance("AES/GCM/NoPadding")
        engine.init(Cipher.ENCRYPT_MODE, key())
        return listOf(
            PREFIX,
            Base64.encodeToString(engine.iv, Base64.NO_WRAP),
            Base64.encodeToString(engine.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        ).joinToString(":")
    }

    fun decrypt(value: String): String {
        if (!value.startsWith("$PREFIX:")) return value // legacy plaintext migration
        val parts = value.split(':', limit = 3)
        require(parts.size == 3) { "Invalid encrypted session" }
        val engine = Cipher.getInstance("AES/GCM/NoPadding")
        engine.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP))
        )
        return engine.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    companion object {
        private const val ALIAS = "multiweb_provider_sessions_v1"
        private const val PREFIX = "enc_v1"
    }
}
