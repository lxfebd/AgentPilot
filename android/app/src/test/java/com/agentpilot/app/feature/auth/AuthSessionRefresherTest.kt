package com.agentpilot.app.feature.auth

import com.agentpilot.app.api.AccessTokenRefreshResult
import com.agentpilot.app.api.ApiException
import com.agentpilot.app.api.AuthResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionRefresherTest {

    @Test
    fun reusesAnAccessTokenAnotherCallerAlreadyRotated() {
        val store = FakeRefreshStore(accessToken = "fresh-access")
        val calls = AtomicInteger(0)
        val refresher = AuthSessionRefresher(store, { _, _ ->
            calls.incrementAndGet()
            auth(accessToken = "should-not-be-used")
        })

        val result = refresher.refresh("https://pilot.example.com", "expired-access")

        assertEquals(AccessTokenRefreshResult.Refreshed("fresh-access"), result)
        assertEquals(0, calls.get())
        assertFalse(store.saved)
    }

    @Test
    fun refreshesOnceThenServesLaterCallersTheRotatedToken() {
        val store = FakeRefreshStore()
        val calls = AtomicInteger(0)
        val refresher = AuthSessionRefresher(store, { serverUrl, refreshToken ->
            calls.incrementAndGet()
            assertEquals("https://pilot.example.com", serverUrl)
            assertEquals("refresh-1", refreshToken)
            auth(accessToken = "new-access", refreshToken = "refresh-2")
        })

        val first = refresher.refresh("https://pilot.example.com", "expired-access")

        assertEquals(AccessTokenRefreshResult.Refreshed("new-access"), first)
        assertEquals(1, calls.get())
        assertTrue(store.saved)
        assertEquals("new-access", store.accessToken)
        assertEquals("refresh-2", store.refreshToken)

        // A second 401 for the old token is served from the store, no extra request.
        val second = refresher.refresh("https://pilot.example.com", "expired-access")
        assertEquals(AccessTokenRefreshResult.Refreshed("new-access"), second)
        assertEquals(1, calls.get())
    }

    @Test
    fun rejectsWithoutANetworkCallWhenNoRefreshTokenIsStored() {
        val store = FakeRefreshStore(refreshToken = "")
        val calls = AtomicInteger(0)
        val refresher = AuthSessionRefresher(store, { _, _ ->
            calls.incrementAndGet()
            auth(accessToken = "unused")
        })

        assertEquals(
            AccessTokenRefreshResult.Rejected,
            refresher.refresh("https://pilot.example.com", "expired-access"),
        )
        assertEquals(0, calls.get())
    }

    @Test
    fun reportsARejectedRefreshOnceAndCachesTheDeadToken() {
        val store = FakeRefreshStore()
        val calls = AtomicInteger(0)
        val rejections = AtomicInteger(0)
        val refresher = AuthSessionRefresher(
            store,
            { _, _ ->
                calls.incrementAndGet()
                throw ApiException("refresh token expired", statusCode = 401)
            },
            onSessionRejected = { rejections.incrementAndGet() },
        )

        assertEquals(
            AccessTokenRefreshResult.Rejected,
            refresher.refresh("https://pilot.example.com", "expired-access"),
        )
        assertEquals(1, calls.get())
        assertEquals(1, rejections.get())

        assertEquals(
            AccessTokenRefreshResult.Rejected,
            refresher.refresh("https://pilot.example.com", "expired-access"),
        )
        assertEquals(1, calls.get())
        assertEquals(1, rejections.get())
    }

    @Test
    fun transientRefreshFailureDoesNotRejectTheSession() {
        val store = FakeRefreshStore()
        val rejections = AtomicInteger(0)
        val refresher = AuthSessionRefresher(
            store,
            { _, _ -> throw ApiException("upstream unavailable", statusCode = 503) },
            onSessionRejected = { rejections.incrementAndGet() },
        )

        val result = refresher.refresh("https://pilot.example.com", "expired-access")

        assertTrue(result is AccessTokenRefreshResult.Failed)
        assertEquals(0, rejections.get())
        assertFalse(store.saved)
    }

    @Test
    fun keepsThePreviousRefreshTokenWhenTheResponseOmitsIt() {
        val store = FakeRefreshStore()
        val refresher = AuthSessionRefresher(store, { _, _ ->
            auth(accessToken = "new-access", refreshToken = null)
        })

        refresher.refresh("https://pilot.example.com", "expired-access")

        assertEquals("refresh-1", store.refreshToken)
    }

    @Test
    fun concurrentRefreshesShareOneNetworkExchange() {
        val store = FakeRefreshStore()
        val calls = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refresher = AuthSessionRefresher(store, { _, _ ->
            calls.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            auth(accessToken = "new-access", refreshToken = "refresh-2")
        })

        val results = arrayOfNulls<AccessTokenRefreshResult>(2)
        val first = Thread { results[0] = refresher.refresh("https://pilot.example.com", "expired-access") }
        first.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = Thread { results[1] = refresher.refresh("https://pilot.example.com", "expired-access") }
        second.start()
        // Let the second caller reach the single-flight lock before the first one finishes.
        Thread.sleep(50)
        release.countDown()
        first.join(5_000)
        second.join(5_000)

        assertEquals(1, calls.get())
        assertEquals(AccessTokenRefreshResult.Refreshed("new-access"), results[0])
        assertEquals(AccessTokenRefreshResult.Refreshed("new-access"), results[1])
        assertEquals(1, store.saveCount)
    }
}

private fun auth(
    accessToken: String,
    refreshToken: String? = "refresh-1",
): AuthResponse = AuthResponse(
    userId = "user-1",
    role = "user",
    accessToken = accessToken,
    tokenType = "bearer",
    serverTime = "2026-10-31T00:00:00Z",
    refreshToken = refreshToken,
    expiresAt = "2026-11-30T00:00:00Z",
)

private class FakeRefreshStore(
    var serverUrl: String = "https://pilot.example.com",
    var accessToken: String = "expired-access",
    var refreshToken: String = "refresh-1",
) : SessionRefreshStore {
    var saved: Boolean = false
    var saveCount: Int = 0
    var savedAuth: AuthResponse? = null

    override fun readServerUrl(): String = serverUrl

    override fun readAccessToken(): String = accessToken

    override fun readRefreshToken(): String = refreshToken

    override fun saveAuthSession(serverUrl: String, auth: AuthResponse) {
        saved = true
        saveCount += 1
        savedAuth = auth
        accessToken = auth.accessToken
        auth.refreshToken?.let { refreshToken = it }
    }
}
