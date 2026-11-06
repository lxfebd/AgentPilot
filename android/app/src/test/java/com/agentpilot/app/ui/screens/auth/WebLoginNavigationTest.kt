package com.agentpilot.app.ui.screens.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoginNavigationTest {
    private val origin = "https://server.example.com"

    @Test fun sameOriginStaysInWebView() {
        assertTrue(isSameLoginOrigin("https://server.example.com/#/mobile-oauth?state=x", origin))
    }

    @Test fun sameOriginWithPortStaysInWebView() {
        assertTrue(isSameLoginOrigin("http://192.168.1.10:5174/login", "http://192.168.1.10:5174"))
    }

    @Test fun otherHostLeavesWebView() {
        assertFalse(isSameLoginOrigin("https://evil.example.com/sso", origin))
    }

    @Test fun differentPortLeavesWebView() {
        assertFalse(isSameLoginOrigin("https://server.example.com:8443/x", origin))
    }

    @Test fun schemeMismatchLeavesWebView() {
        assertFalse(isSameLoginOrigin("http://server.example.com/x", origin))
    }

    @Test fun hostCaseIsNormalized() {
        assertTrue(isSameLoginOrigin("HTTPS://Server.EXAMPLE.com/x", origin))
    }

    @Test fun nonHttpSchemeRejected() {
        assertFalse(isSameLoginOrigin("agent-pilot://oauth/callback?code=x", origin))
    }

    @Test fun unparseableUrlRejected() {
        assertFalse(isSameLoginOrigin("not a url", origin))
    }

    @Test fun missingHostRejected() {
        assertFalse(isSameLoginOrigin("https:///path", origin))
    }
}
