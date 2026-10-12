package com.agentpilot.app.feature.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.agentpilot.app.api.AuthResponse
import com.agentpilot.app.api.MobileLoginExchangeResponse
import com.agentpilot.app.api.normalizeServerOrigin

/**
 * Stores the auth session in an EncryptedSharedPreferences (AES-256 keyed by
 * an Android Keystore master key) so tokens never land on disk in plaintext.
 *
 * The legacy plaintext store (`agent_pilot_auth`) is migrated once on first
 * use: values are copied into the encrypted store (`agent_pilot_auth_v2`) and
 * the plaintext file is then removed.
 *
 * Backup / device-transfer exclusions for both file names live in
 * res/xml/backup_rules.xml and res/xml/data_extraction_rules.xml.
 */
class AuthSessionStore(context: Context) : AuthSessionReader {
    private val preferences = SessionPreferences.open(appContext = context.applicationContext)

    override fun readServerUrl(): String {
        val stored = preferences.getString(KEY_SERVER_URL, "").orEmpty()
        return normalizeServerOrigin(stored).orEmpty()
    }

    override fun readAccessToken(): String {
        return preferences.getString(KEY_ACCESS_TOKEN, "").orEmpty()
    }

    fun readUserId(): String {
        return preferences.getString(KEY_USER_ID, "").orEmpty()
    }

    fun readRole(): String {
        return preferences.getString(KEY_ROLE, "").orEmpty()
    }

    fun hasAuthSession(): Boolean {
        return readServerUrl().isNotBlank() && readAccessToken().isNotBlank()
    }

    fun saveServerUrl(serverUrl: String) {
        preferences.edit()
            .putString(KEY_SERVER_URL, serverUrl.asServerOrigin())
            .apply()
    }

    fun saveAuthSession(serverUrl: String, auth: AuthResponse) {
        preferences.edit()
            .putString(KEY_SERVER_URL, serverUrl.asServerOrigin())
            .putString(KEY_ACCESS_TOKEN, auth.accessToken)
            .putString(KEY_TOKEN_TYPE, auth.tokenType)
            .putString(KEY_USER_ID, auth.userId)
            .putString(KEY_ROLE, auth.role)
            .apply()
    }

    fun saveMobileAuthSession(serverUrl: String, exchange: MobileLoginExchangeResponse) {
        preferences.edit()
            .putString(KEY_SERVER_URL, serverUrl.asServerOrigin())
            .putString(KEY_ACCESS_TOKEN, exchange.auth.accessToken)
            .putString(KEY_TOKEN_TYPE, exchange.auth.tokenType)
            .putString(KEY_USER_ID, exchange.auth.userId)
            .putString(KEY_ROLE, exchange.auth.role)
            .putString(KEY_REFRESH_TOKEN, exchange.refreshToken)
            .putString(KEY_REFRESH_EXPIRES_AT, exchange.expiresAt)
            .apply()
    }

    @Synchronized
    fun clearAuthSession() {
        clearAuthSessionKeepingServerUrl()
    }

    @Synchronized
    fun clearAuthSessionIfTokenMatches(accessToken: String): Boolean {
        if (!shouldClearAuthSession(readAccessToken(), accessToken)) return false
        clearAuthSessionKeepingServerUrl()
        return true
    }

    private fun clearAuthSessionKeepingServerUrl() {
        val serverUrl = readServerUrl()
        preferences.edit()
            .clear()
            .putString(KEY_SERVER_URL, serverUrl)
            .apply()
    }

    private fun String.asServerOrigin(): String {
        return requireNotNull(normalizeServerOrigin(this)) {
            "Server URL must be an HTTP(S) origin."
        }
    }

    companion object {
        internal const val LEGACY_PREFERENCES_NAME = "agent_pilot_auth"
        internal const val PREFERENCES_NAME = "agent_pilot_auth_v2"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ROLE = "role"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"

        /** All keys that must be copied over when migrating from the legacy plaintext store. */
        internal val LEGACY_KEYS = listOf(
            KEY_SERVER_URL,
            KEY_ACCESS_TOKEN,
            KEY_TOKEN_TYPE,
            KEY_USER_ID,
            KEY_ROLE,
            KEY_REFRESH_TOKEN,
            KEY_REFRESH_EXPIRES_AT,
        )
    }
}

/**
 * Process-wide holder for the encrypted session store.
 *
 * EncryptedSharedPreferences performs Keystore + file IO on creation, so the
 * instance is created once per process and reused by every AuthSessionStore.
 * The legacy plaintext store (`agent_pilot_auth`, shipped before 2.1.x) is
 * migrated into the encrypted store a single time and then deleted.
 */
private object SessionPreferences {
    @Volatile
    private var cached: android.content.SharedPreferences? = null

    private var migrated = false

    fun open(appContext: Context): android.content.SharedPreferences {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: run {
                val encrypted = runCatching {
                    val masterKey = MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        appContext,
                        AuthSessionStore.PREFERENCES_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                }.getOrNull()

                if (encrypted != null) {
                    migrateLegacyStore(appContext, encrypted)
                    encrypted
                } else {
                    // Keystore unavailable/corrupt (rare, e.g. after a broken restore):
                    // degrade to the legacy plaintext store so the app stays usable.
                    // Backup exclusions still cover this file name.
                    appContext.getSharedPreferences(
                        AuthSessionStore.LEGACY_PREFERENCES_NAME,
                        Context.MODE_PRIVATE,
                    )
                }
            }.also { store ->
                cached = store
            }
        }
    }

    private fun migrateLegacyStore(context: Context, encrypted: android.content.SharedPreferences) {
        if (migrated) return
        synchronized(this) {
            if (migrated) return

            val legacy = context.getSharedPreferences(
                AuthSessionStore.LEGACY_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            val hasSession = legacy.all.isNotEmpty()
            if (hasSession) {
                val editor = encrypted.edit()
                for (key in AuthSessionStore.LEGACY_KEYS) {
                    val value = legacy.getString(key, null)
                    if (value != null) editor.putString(key, value)
                }
                editor.apply()
            }

            // Drop the legacy file entirely (also removes server_url when no session existed).
            context.deleteSharedPreferences(AuthSessionStore.LEGACY_PREFERENCES_NAME)
            migrated = true
        }
    }
}

interface AuthSessionReader {
    fun readServerUrl(): String

    fun readAccessToken(): String
}

internal fun shouldClearAuthSession(currentAccessToken: String, unauthorizedAccessToken: String): Boolean {
    return unauthorizedAccessToken.isNotBlank() && currentAccessToken == unauthorizedAccessToken
}
