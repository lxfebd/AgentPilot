package com.agentpilot.app.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the mobile-login QR payload parser.
 *
 * Regression context: the rebrand changed the QR payload `type` from
 * `agents-anywhere.mobile-login` to `agent-pilot.mobile-login`.  Desktop/Web
 * generators whose bundles had not been rebuilt yet still emit the old type,
 * which made every scan fail with "Scan a valid AgentPilot QR code." even
 * though the scanned code was genuine.  Both types must be accepted.
 *
 * Parsing is pure JSON logic, so it needs no Android context, camera, or
 * network.
 */
class AuthControllerQrPayloadTest {

    private fun payload(
        type: String,
        serverUrl: String? = "https://example.com",
        userId: String? = "user_1",
        loginToken: String? = "token_abc",
        expiresAt: String? = "2026-10-14T12:00:00Z",
    ): String = buildString {
        append("{")
        append("\"type\":").append(jsonString(type)).append(",")
        if (serverUrl != null) append("\"serverUrl\":").append(jsonString(serverUrl)).append(",")
        if (userId != null) append("\"userId\":").append(jsonString(userId)).append(",")
        if (loginToken != null) append("\"loginToken\":").append(jsonString(loginToken)).append(",")
        if (expiresAt != null) append("\"expiresAt\":").append(jsonString(expiresAt))
        append("}")
    }

    private fun jsonString(value: String) =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun parsesCurrentAgentPilotPayload() {
        val parsed = parseMobileLoginQrPayloadInternal(payload(type = "agent-pilot.mobile-login"))

        assertNotNull(parsed)
        assertEquals("https://example.com", parsed!!.serverUrl)
        assertEquals("user_1", parsed.userId)
        assertEquals("token_abc", parsed.loginToken)
        assertEquals("2026-10-14T12:00:00Z", parsed.expiresAt)
    }

    @Test
    fun parsesLegacyAgentsAnywherePayload() {
        // Pre-rebrand desktop/Web bundles still emit this type.
        val parsed = parseMobileLoginQrPayloadInternal(payload(type = "agents-anywhere.mobile-login"))

        assertNotNull(parsed)
        assertEquals("https://example.com", parsed!!.serverUrl)
        assertEquals("user_1", parsed.userId)
        assertEquals("token_abc", parsed.loginToken)
    }

    @Test
    fun fallsBackToWebUrlField() {
        val raw = payload(type = "agent-pilot.mobile-login", serverUrl = null)
            .replace("{", "{\"webUrl\":\"https://example.com\",")

        val parsed = parseMobileLoginQrPayloadInternal(raw)

        assertNotNull(parsed)
        assertEquals("https://example.com", parsed!!.serverUrl)
    }

    @Test
    fun rejectsUnknownPayloadType() {
        assertNull(parseMobileLoginQrPayloadInternal(payload(type = "some.other.app")))
    }

    @Test
    fun rejectsMissingType() {
        val raw = """
            {"serverUrl":"https://example.com","userId":"u","loginToken":"t"}
        """.trimIndent()
        assertNull(parseMobileLoginQrPayloadInternal(raw))
    }

    @Test
    fun rejectsMissingLoginToken() {
        assertNull(
            parseMobileLoginQrPayloadInternal(
                payload(type = "agent-pilot.mobile-login", loginToken = null),
            ),
        )
    }

    @Test
    fun rejectsBlankLoginToken() {
        assertNull(
            parseMobileLoginQrPayloadInternal(
                payload(type = "agent-pilot.mobile-login", loginToken = "   "),
            ),
        )
    }

    @Test
    fun rejectsMissingUserId() {
        assertNull(
            parseMobileLoginQrPayloadInternal(
                payload(type = "agent-pilot.mobile-login", userId = null),
            ),
        )
    }

    @Test
    fun rejectsUnusableServerUrl() {
        assertNull(
            parseMobileLoginQrPayloadInternal(
                payload(type = "agent-pilot.mobile-login", serverUrl = "   "),
            ),
        )
    }

    @Test
    fun rejectsServerUrlWithQueryString() {
        // The server origin is used to build every later API URL, so a URL that
        // carries a query or fragment must not be accepted.
        assertNull(
            parseMobileLoginQrPayloadInternal(
                payload(
                    type = "agent-pilot.mobile-login",
                    serverUrl = "https://example.com?debug=1",
                ),
            ),
        )
    }

    @Test
    fun rejectsServerUrlWithFragment() {
        assertNull(
            parseMobileLoginQrPayloadInternal(
                payload(
                    type = "agent-pilot.mobile-login",
                    serverUrl = "https://example.com#frag",
                ),
            ),
        )
    }

    @Test
    fun rejectsServerUrlWithCredentials() {
        assertNull(
            parseMobileLoginQrPayloadInternal(
                payload(
                    type = "agent-pilot.mobile-login",
                    serverUrl = "https://user:pass@example.com",
                ),
            ),
        )
    }

    @Test
    fun rejectsServerUrlWithUnknownPath() {
        assertNull(
            parseMobileLoginQrPayloadInternal(
                payload(
                    type = "agent-pilot.mobile-login",
                    serverUrl = "https://example.com/some/other/path",
                ),
            ),
        )
    }

    @Test
    fun rejectsNonJsonPayload() {
        assertNull(parseMobileLoginQrPayloadInternal("not json at all"))
    }

    @Test
    fun rejectsEmptyPayload() {
        assertNull(parseMobileLoginQrPayloadInternal(""))
    }

    @Test
    fun rejectsJsonArrayPayload() {
        assertNull(parseMobileLoginQrPayloadInternal("[1,2,3]"))
    }

    @Test
    fun normalizesServerUrlTrailingSlashAndScheme() {
        val parsed = parseMobileLoginQrPayloadInternal(
            payload(type = "agent-pilot.mobile-login", serverUrl = "example.com/"),
        )

        assertNotNull(parsed)
        assertEquals("https://example.com", parsed!!.serverUrl)
    }

    @Test
    fun acceptsApiNamespacePath() {
        // The v2 namespace is allowed so a URL copied from the browser works.
        val parsed = parseMobileLoginQrPayloadInternal(
            payload(
                type = "agent-pilot.mobile-login",
                serverUrl = "https://example.com/api/v2",
            ),
        )

        assertNotNull(parsed)
        assertEquals("https://example.com", parsed!!.serverUrl)
    }

    @Test
    fun keepsPortInServerUrl() {
        val parsed = parseMobileLoginQrPayloadInternal(
            payload(
                type = "agent-pilot.mobile-login",
                serverUrl = "http://127.0.0.1:5174",
            ),
        )

        assertNotNull(parsed)
        assertEquals("http://127.0.0.1:5174", parsed!!.serverUrl)
    }

    @Test
    fun allowsMissingExpiresAt() {
        val parsed = parseMobileLoginQrPayloadInternal(
            payload(type = "agent-pilot.mobile-login", expiresAt = null),
        )

        assertNotNull(parsed)
        assertNull(parsed!!.expiresAt)
    }

    @Test
    fun ignoresBlankExpiresAt() {
        val parsed = parseMobileLoginQrPayloadInternal(
            payload(type = "agent-pilot.mobile-login", expiresAt = "  "),
        )

        assertNotNull(parsed)
        assertNull(parsed!!.expiresAt)
    }

    @Test
    fun trimsWhitespaceAroundServerUrl() {
        val parsed = parseMobileLoginQrPayloadInternal(
            payload(
                type = "agent-pilot.mobile-login",
                serverUrl = "  https://example.com  ",
            ),
        )

        assertNotNull(parsed)
        assertEquals("https://example.com", parsed!!.serverUrl)
    }
}
