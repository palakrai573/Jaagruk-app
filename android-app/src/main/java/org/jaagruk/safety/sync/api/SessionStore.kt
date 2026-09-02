package org.jaagruk.safety.sync.api

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Supervisor session tokens, Keystore-encrypted at rest.
 *
 * Only supervisors and officers authenticate against the server; workers never do. A worker's daily
 * login is a local PIN check, because an OTP or a password round-trip would need connectivity that
 * is not there.
 *
 * If the encrypted store cannot be opened, this degrades to holding tokens in memory for the process
 * lifetime rather than crashing. Losing a session is an inconvenience; losing the app on a handset
 * whose keystore was reset would strand a whole site.
 */
class SessionStore(context: Context) {

    private companion object {
        const val TAG = "SessionStore"
        const val PREFS = "jaagruk_session"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ROLE = "role"
        const val KEY_SITE = "site_id"
        const val KEY_NAME = "full_name"
        const val KEY_EXPIRES_AT = "expires_at_ms"
    }

    private var memoryOnly: MutableMap<String, String>? = null

    private val preferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w(TAG, "encrypted session store unavailable; holding tokens in memory only", e)
        memoryOnly = mutableMapOf()
        null
    }

    val isEncrypted: Boolean get() = preferences != null

    var accessToken: String?
        get() = read(KEY_ACCESS)
        set(value) = write(KEY_ACCESS, value)

    var refreshToken: String?
        get() = read(KEY_REFRESH)
        set(value) = write(KEY_REFRESH, value)

    val role: String? get() = read(KEY_ROLE)

    val siteId: String? get() = read(KEY_SITE)

    val fullName: String? get() = read(KEY_NAME)

    val isAuthenticated: Boolean get() = !accessToken.isNullOrBlank()

    /**
     * True when the access token is within a minute of expiry.
     *
     * Refreshing proactively avoids a guaranteed 401 in the middle of draining a large batch, which
     * would otherwise cost a whole upload round trip.
     */
    val accessTokenNearlyExpired: Boolean
        get() {
            val expiresAt = read(KEY_EXPIRES_AT)?.toLongOrNull() ?: return false
            return System.currentTimeMillis() > expiresAt - 60_000L
        }

    fun save(response: TokenResponse) {
        write(KEY_ACCESS, response.accessToken)
        write(KEY_REFRESH, response.refreshToken)
        write(KEY_ROLE, response.role)
        write(KEY_SITE, response.siteId)
        write(KEY_NAME, response.fullName)
        write(
            KEY_EXPIRES_AT,
            (System.currentTimeMillis() + response.expiresInSeconds * 1_000L).toString(),
        )
    }

    /**
     * Clears the session.
     *
     * Deliberately touches nothing else. The sync queue, the site signing key and every stored
     * certificate survive: a revoked supervisor session must never take a worker's unsynced training
     * record with it.
     */
    fun clear() {
        listOf(KEY_ACCESS, KEY_REFRESH, KEY_ROLE, KEY_SITE, KEY_NAME, KEY_EXPIRES_AT)
            .forEach { write(it, null) }
    }

    private fun read(key: String): String? =
        preferences?.getString(key, null) ?: memoryOnly?.get(key)

    private fun write(key: String, value: String?) {
        val prefs = preferences
        if (prefs != null) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        } else {
            if (value == null) memoryOnly?.remove(key) else memoryOnly?.put(key, value)
        }
    }
}
