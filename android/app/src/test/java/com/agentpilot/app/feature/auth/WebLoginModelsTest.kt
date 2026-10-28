package com.agentpilot.app.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoginModelsTest {
    private val session = WebLoginSession(
        serverUrl = "https://smartcare.aifacex.top",
        oauthWebOrigin = "https://smartcare.aifacex.top",
        authorizeUrl = "https://smartcare.aifacex.top/#/mobile-oauth",
        state = "test-state-1234",
        codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
    )

    @Test fun pkceChallengeMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun parseCallbackAcceptsValidSuccess() {
        val result = parseWebLoginCallback(
            "agent-pilot://oauth/callback?code=auth-code&state=test-state-1234",
            session,
        )
        assertEquals(WebLoginCallback.Success("auth-code"), result)
    }

    @Test fun parseCallbackRejectsStateMismatch() {
        val result = parseWebLoginCallback(
            "agent-pilot://oauth/callback?code=x&state=wrong-state",
            session,
        )
        assertTrue(result is WebLoginCallback.Invalid)
    }

    @Test fun parseCallbackHandlesAccessDenied() {
        val result = parseWebLoginCallback(
            "agent-pilot://oauth/callback?error=access_denied&state=test-state-1234",
            session,
        )
        assertEquals(WebLoginCallback.Error("Sign in was cancelled."), result)
    }

    @Test fun parseCallbackHandlesOtherError() {
        val result = parseWebLoginCallback(
            "agent-pilot://oauth/callback?error=server_error&error_description=boom&state=test-state-1234",
            session,
        )
        assertEquals(WebLoginCallback.Error("boom"), result)
    }

    @Test fun parseCallbackRejectsMissingCode() {
        val result = parseWebLoginCallback(
            "agent-pilot://oauth/callback?state=test-state-1234",
            session,
        )
        assertTrue(result is WebLoginCallback.Invalid)
    }

    @Test fun parseCallbackRejectsWrongHost() {
        val result = parseWebLoginCallback(
            "agent-pilot://evil/callback?code=x&state=test-state-1234",
            session,
        )
        assertTrue(result is WebLoginCallback.Invalid)
    }

    @Test fun parseCallbackRejectsFragment() {
        val result = parseWebLoginCallback(
            "agent-pilot://oauth/callback?code=x&state=test-state-1234#frag",
            session,
        )
        assertTrue(result is WebLoginCallback.Invalid)
    }

    @Test fun parseCallbackIsSingleUse() {
        val url = "agent-pilot://oauth/callback?code=x&state=test-state-1234"
        assertTrue(parseWebLoginCallback(url, session) is WebLoginCallback.Success)
        assertTrue(parseWebLoginCallback(url, session) is WebLoginCallback.Invalid)
    }

    @Test fun createSessionBuildsPkceAuthorizeUrl() {
        val created = createWebLoginSession(
            server = LoginServerConnection(
                serverUrl = "https://smartcare.aifacex.top",
                oauthWebOrigin = "https://smartcare.aifacex.top",
            ),
            codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
            state = "st-1234567890",
        )
        assertTrue(created.authorizeUrl.startsWith("https://smartcare.aifacex.top/#/mobile-oauth?"))
        assertTrue(created.authorizeUrl.contains("code_challenge_method=S256"))
        assertTrue(created.authorizeUrl.contains("code_challenge="))
        assertTrue(created.authorizeUrl.contains("state=st-1234567890"))
        assertTrue(created.authorizeUrl.contains("redirect_uri=agent-pilot%3A%2F%2Foauth%2Fcallback"))
    }

    @Test fun createSessionDefaultsToRandomStateAndVerifier() {
        val server = LoginServerConnection("https://a.example", "https://a.example")
        val a = createWebLoginSession(server)
        val b = createWebLoginSession(server)
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.codeVerifier, b.codeVerifier)
        // randomUrlSafeString 的入参是字节数：24B -> 32 个 base64url 字符，32B -> 43 个。
        // 熵 192/256 bit 充足，verifier 43 字符符合 PKCE 43-128 区间。
        assertEquals(32, a.state.length)
        assertEquals(43, a.codeVerifier.length)
    }
}
