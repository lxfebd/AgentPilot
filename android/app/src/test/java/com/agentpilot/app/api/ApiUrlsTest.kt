package com.agentpilot.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiUrlsTest {
    @Test fun normalizeKeepsExplicitHttpsOrigin() {
        assertEquals("https://smartcare.aifacex.top", normalizeServerOrigin("https://smartcare.aifacex.top/"))
    }

    @Test fun normalizeAddsHttpsForPublicHost() {
        assertEquals("https://example.com", normalizeServerOrigin("example.com"))
    }

    @Test fun normalizeAddsHttpForLanHostWithPort() {
        assertEquals("http://192.168.1.10:8000", normalizeServerOrigin("192.168.1.10:8000"))
    }

    @Test fun normalizeAcceptsApiNamespacePath() {
        assertEquals("https://example.com", normalizeServerOrigin("https://example.com/api/v2"))
    }

    @Test fun normalizeRejectsOtherPaths() {
        assertNull(normalizeServerOrigin("https://example.com/other"))
    }

    @Test fun normalizeRejectsCredentialsQueryAndFragment() {
        assertNull(normalizeServerOrigin("https://user:pass@example.com"))
        assertNull(normalizeServerOrigin("https://example.com/?q=1"))
        assertNull(normalizeServerOrigin("https://example.com/#frag"))
    }

    @Test fun normalizeRejectsNonHttpScheme() {
        assertNull(normalizeServerOrigin("ftp://example.com"))
        assertNull(normalizeServerOrigin("file:///etc/passwd"))
    }

    @Test fun normalizeRejectsBlank() {
        assertNull(normalizeServerOrigin(""))
        assertNull(normalizeServerOrigin("   "))
    }

    @Test fun localNetworkHosts() {
        assertTrue(usesLocalNetworkHost("localhost"))
        assertTrue(usesLocalNetworkHost("127.0.0.1"))
        assertTrue(usesLocalNetworkHost("10.0.0.5"))
        assertTrue(usesLocalNetworkHost("172.16.0.1"))
        assertTrue(usesLocalNetworkHost("172.31.255.255"))
        assertTrue(usesLocalNetworkHost("192.168.1.1"))
        assertTrue(usesLocalNetworkHost("mybox.local"))
        assertFalse(usesLocalNetworkHost("example.com"))
        assertFalse(usesLocalNetworkHost("172.15.0.1"))
        assertFalse(usesLocalNetworkHost("172.32.0.1"))
        assertFalse(usesLocalNetworkHost("256.1.1.1"))
        assertFalse(usesLocalNetworkHost("1.2.3"))
        assertFalse(usesLocalNetworkHost(""))
    }
}
