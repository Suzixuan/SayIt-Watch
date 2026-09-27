package com.sayit.watch.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * App-private debug storage for the receiver destination and development token.
 * Plain SharedPreferences inside the application sandbox; nothing is exported.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sayit_watch_debug", Context.MODE_PRIVATE)

    var receiverIp: String
        get() = prefs.getString(KEY_IP, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_IP, value).apply()

    var receiverPort: String
        get() = prefs.getString(KEY_PORT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PORT, value).apply()

    var devToken: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    fun isValidDestination(): Boolean =
        DestinationValidator.validate(receiverIp, receiverPort) is DestinationValidator.ValidationResult.Valid

    /** True when the stored token trims to exactly 64 hex characters. */
    fun hasValidToken(): Boolean = DevTokenValidator.isValid(devToken)

    /**
     * A user-chosen local label for one authenticated endpoint. The alias never
     * leaves the Watch and does not weaken discovery/authentication.
     */
    fun computerAlias(ip: String, port: Int): String =
        prefs.getString(ComputerAliasPolicy.preferenceKey(ip, port), "").orEmpty()

    /** An empty/invalid label restores the deterministic fallback name. */
    fun setComputerAlias(ip: String, port: Int, value: String) {
        val key = ComputerAliasPolicy.preferenceKey(ip, port)
        val normalized = ComputerAliasPolicy.normalize(value)
        prefs.edit().apply {
            if (normalized.isEmpty()) remove(key) else putString(key, normalized)
        }.apply()
    }

    private companion object {
        const val KEY_IP = "receiver_ip"
        const val KEY_PORT = "receiver_port"
        const val KEY_TOKEN = "dev_token"
    }
}

/** Pure policy kept outside Android APIs so normalization is JVM-testable. */
object ComputerAliasPolicy {
    const val MAX_LENGTH: Int = 16

    fun normalize(value: String): String =
        value.map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }.joinToString("")
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_LENGTH)
            .trim()

    fun preferenceKey(ip: String, port: Int): String = "computer_alias_${ip}_$port"
}
