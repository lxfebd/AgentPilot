package com.agentpilot.app.api

/** Outcome of exchanging the stored refresh token for a fresh access token. */
sealed interface AccessTokenRefreshResult {
    /** A usable access token: replay the failed request with it exactly once. */
    data class Refreshed(val accessToken: String) : AccessTokenRefreshResult

    /**
     * The server rejected the refresh token: invalid, expired, disabled user,
     * or the password changed. The local session cannot be repaired.
     */
    data object Rejected : AccessTokenRefreshResult

    /**
     * The refresh could not be completed for a non-auth reason (network down,
     * unexpected response). The session is still valid, so callers must not
     * sign the user out.
     */
    data class Failed(val error: Throwable? = null) : AccessTokenRefreshResult
}

/**
 * Single entry point for renewing an expired access token.
 *
 * Implementations are shared by every API caller and must serialize concurrent
 * refreshes for the same failed token (single-flight): one network exchange,
 * every waiting caller reuses its result.
 */
fun interface AccessTokenRefresher {
    fun refresh(serverUrl: String, failedAccessToken: String): AccessTokenRefreshResult
}
