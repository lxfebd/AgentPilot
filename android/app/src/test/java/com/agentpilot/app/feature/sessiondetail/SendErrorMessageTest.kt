package com.agentpilot.app.feature.sessiondetail

import com.agentpilot.app.R
import com.agentpilot.app.api.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
