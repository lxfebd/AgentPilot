package com.agentpilot.app.ui.screens.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoginNavigationTest {
    private val origin = "https://smartcare.aifacex.top"

    @Test fun sameOriginStaysInWebView() {
        assertTrue(isSameLoginOrigin("https://smartcare.aifacex.top/#/mobile-oauth?state=x", origin))
    }

    @Test fun sameOriginWithPortStaysInWebView() {
        assertTrue(isSameLoginOrigin("http://192.168.1.10:5174/login", "http://192.168.1.10:5174"))
    }

    @Test fun otherHostLeavesWebView() {
        assertFalse(isSameLoginOrigin("https://evil.example.com/sso", origin))
    }

    @Test fun differentPortLeavesWebView() {
        assertFalse(isSameLoginOrigin("https://smartcare.aifacex.top:8443/x", origin))
    }

    @Test fun schemeMismatchLeavesWebView() {
        assertFalse(isSameLoginOrigin("http://smartcare.aifacex.top/x", origin))
    }

    @Test fun hostCaseIsNormalized() {
        assertTrue(isSameLoginOrigin("HTTPS://SmartCare.AIFACEX.top/x", origin))
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
