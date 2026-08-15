package com.wenwei.deepseekproxy.proxy

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.URI

/** Security boundary shared by every local gateway endpoint. */
object LocalGatewaySecurity {
    const val HEADER_SCHEME = "Bearer"
    const val TOKEN_BYTES = 32

    fun generateToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun accepts(authorization: String?, expectedToken: String): Boolean {
        if (expectedToken.isBlank()) return false
        val header = authorization?.trim().orEmpty()
        val separator = header.indexOf(' ')
        if (separator <= 0 || !header.substring(0, separator).equals(HEADER_SCHEME, ignoreCase = true)) {
            return false
        }
        val supplied = header.substring(separator + 1).trim()
        if (supplied.isBlank()) return false
        return MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            expectedToken.toByteArray(Charsets.UTF_8),
        )
    }

    fun shouldAuthorizeGeneratedImage(url: String, expectedPort: Int): Boolean = runCatching {
        val uri = URI(url)
        val prefix = "/v1/generated-images/"
        val rawPath = uri.rawPath.orEmpty()
        val imageId = rawPath.removePrefix(prefix)
        uri.scheme.equals("http", ignoreCase = true) &&
            uri.host?.lowercase() in setOf("127.0.0.1", "localhost", "::1", "[::1]") &&
            uri.port == expectedPort &&
            rawPath.startsWith(prefix) &&
            imageId.matches(Regex("[A-Za-z0-9._-]+"))
    }.getOrDefault(false)
}

/** App-private token storage. A token is created only when the user opens/starts the app. */
class LocalGatewayTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(): String {
        prefs.getString(KEY_TOKEN, null)?.takeIf(::isValid)?.let { return it }
        return LocalGatewaySecurity.generateToken().also { token ->
            check(prefs.edit().putString(KEY_TOKEN, token).commit()) {
                "Unable to persist local gateway token"
            }
        }
    }

    @Synchronized
    fun reset(): String = LocalGatewaySecurity.generateToken().also { token ->
        check(prefs.edit().putString(KEY_TOKEN, token).commit()) {
            "Unable to persist local gateway token"
        }
    }

    private fun isValid(value: String): Boolean =
        value.length == LocalGatewaySecurity.TOKEN_BYTES * 2 && value.all { it.isDigit() || it in 'a'..'f' }

    private companion object {
        const val PREFS_NAME = "local_gateway_security"
        const val KEY_TOKEN = "bearer_token_v1"
    }
}
