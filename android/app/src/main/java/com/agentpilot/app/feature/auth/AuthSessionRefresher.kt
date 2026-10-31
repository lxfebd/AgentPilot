package com.agentpilot.app.feature.auth

import com.agentpilot.app.api.AccessTokenRefreshResult
import com.agentpilot.app.api.AccessTokenRefresher
import com.agentpilot.app.api.ApiException
import com.agentpilot.app.api.AuthResponse

/** Minimal session surface the refresher needs; implemented by [AuthSessionStore]. */
interface SessionRefreshStore {
    fun readServerUrl(): String

    fun readAccessToken(): String

    fun readRefreshToken(): String

    fun saveAuthSession(serverUrl: String, auth: AuthResponse)
}

/**
 * Rotates the stored refresh token into a fresh access token.
 *
 * A single instance is shared by every API caller, so concurrent 401s from
 * parallel requests are serialized here (single-flight): the first caller
 * performs the network exchange, and every later caller either reuses the
 * freshly stored access token or observes the same rejection without issuing
 * a second refresh request for the same token.
 *
 * A rejected refresh (server says the token is dead) invokes
 * [onSessionRejected] so the app can tell the user the session expired; the
 * caller of [refresh] is responsible for clearing the local session via the
 * usual 401 path.
 */
class AuthSessionRefresher(
    private val store: SessionRefreshStore,
    private val refreshCall: (serverUrl: String, refreshToken: String) -> AuthResponse,
    private val onSessionRejected: () -> Unit = {},
) : AccessTokenRefresher {
    private val lock = Any()
    private var lastRejectedToken: String? = null

    override fun refresh(serverUrl: String, failedAccessToken: String): AccessTokenRefreshResult = synchronized(lock) {
        // Another (earlier) caller already refreshed this token: reuse the
        // stored result instead of asking the server again.
        val current = store.readAccessToken()
        if (current.isNotBlank() && current != failedAccessToken) {
            return@synchronized AccessTokenRefreshResult.Refreshed(current)
        }

        // The rejected token was already rejected once this process lifetime:
        // report the same outcome without a second network round trip.
        if (lastRejectedToken == failedAccessToken) {
            return@synchronized AccessTokenRefreshResult.Rejected
        }

        val refreshToken = store.readRefreshToken()
        if (refreshToken.isBlank()) {
            // Nothing to rotate (session predates refresh tokens): the 401
            // stands and the caller signs the user out.
            lastRejectedToken = failedAccessToken
            return@synchronized AccessTokenRefreshResult.Rejected
        }

        val resolvedServerUrl = serverUrl.ifBlank { store.readServerUrl() }
        return@synchronized try {
            val auth = refreshCall(resolvedServerUrl, refreshToken)
                // A deployment that lags the contract may omit the new refresh
                // token; keep the existing one so rotation continues to work.
                .let { refreshed ->
                    if (refreshed.refreshToken.isNullOrBlank() && !refreshToken.isBlank()) {
                        refreshed.copy(refreshToken = refreshToken)
                    } else {
                        refreshed
                    }
                }
            store.saveAuthSession(resolvedServerUrl, auth)
            AccessTokenRefreshResult.Refreshed(auth.accessToken)
        } catch (error: ApiException) {
            if (error.statusCode == 401) {
                lastRejectedToken = failedAccessToken
                runCatching { onSessionRejected() }
                AccessTokenRefreshResult.Rejected
            } else {
                AccessTokenRefreshResult.Failed(error)
            }
        } catch (error: Exception) {
            AccessTokenRefreshResult.Failed(error)
        }
    }
}
