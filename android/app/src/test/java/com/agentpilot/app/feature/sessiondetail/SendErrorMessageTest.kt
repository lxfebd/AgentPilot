package com.agentpilot.app.feature.sessiondetail

import com.agentpilot.app.R
import com.agentpilot.app.api.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendErrorMessageTest {
    @Test
    fun runtimeUnavailableMapsToLocalizedCopy() {
        val error = ApiException(
            "'rti_9hdNpnGKqySeE8' is not running (status='stopped')",
            statusCode = 502,
            errorCode = "runtime_unavailable",
        )

        assertEquals(R.string.session_send_runtime_not_running, sendErrorResourceId(error))
    }

    @Test
    fun providerErrorsKeepTheirRawMessage() {
        val error = ApiException("request gone", statusCode = 502, errorCode = "codex_error")

        assertNull(sendErrorResourceId(error))
    }

    @Test
    fun errorsWithoutACodeAreNotMapped() {
        assertNull(sendErrorResourceId(IllegalStateException("Runtime rejected the message.")))
        assertNull(sendErrorResourceId(ApiException("Failed to send message.", statusCode = 500)))
    }

    @Test
    fun transportFailuresWithoutAStatusAreIndeterminate() {
        // A read timeout surfaces as an ApiException with no HTTP status: the server may have
        // accepted the message before the response was lost, so the outcome is unknown.
        assertTrue(isIndeterminateSendFailure(ApiException("Could not reach the server.")))
    }

    @Test
    fun serverErrorsAreIndeterminate() {
        assertTrue(isIndeterminateSendFailure(ApiException("Bad gateway.", statusCode = 500)))
        assertTrue(isIndeterminateSendFailure(ApiException("Upstream timeout.", statusCode = 502)))
        assertTrue(isIndeterminateSendFailure(ApiException("Unavailable.", statusCode = 503)))
    }

    @Test
    fun explicitClientRejectionsAreDeterminate() {
        assertFalse(isIndeterminateSendFailure(ApiException("Invalid message.", statusCode = 400)))
        assertFalse(isIndeterminateSendFailure(ApiException("Unauthorized.", statusCode = 401)))
        assertFalse(isIndeterminateSendFailure(ApiException("Too large.", statusCode = 413)))
    }

    @Test
    fun unclassifiedThrowsStayIndeterminate() {
        // Non-API throws carry no status, so we cannot prove a rejection; confirm via echo first.
        assertTrue(isIndeterminateSendFailure(IllegalStateException("Runtime rejected the message.")))
    }
}
