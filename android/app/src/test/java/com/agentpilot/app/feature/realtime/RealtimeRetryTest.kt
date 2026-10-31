package com.agentpilot.app.feature.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeRetryTest {

    @Test
    fun backoffDoublesFromOneSecond() {
        assertEquals(1_000L, reconnectDelayMillis(0, randomUnit = 0.0))
        assertEquals(2_000L, reconnectDelayMillis(1, randomUnit = 0.0))
        assertEquals(4_000L, reconnectDelayMillis(2, randomUnit = 0.0))
        assertEquals(8_000L, reconnectDelayMillis(3, randomUnit = 0.0))
        assertEquals(16_000L, reconnectDelayMillis(4, randomUnit = 0.0))
        assertEquals(32_000L, reconnectDelayMillis(5, randomUnit = 0.0))
    }

    @Test
    fun backoffIsCappedAtSixtySeconds() {
        assertEquals(60_000L, reconnectDelayMillis(6, randomUnit = 0.0))
        assertEquals(60_000L, reconnectDelayMillis(7, randomUnit = 0.0))
        assertEquals(60_000L, reconnectDelayMillis(30, randomUnit = 0.0))
        assertEquals(60_000L, reconnectDelayMillis(6, randomUnit = 1.0))
        assertEquals(60_000L, reconnectDelayMillis(6, randomUnit = 0.5))
    }

    @Test
    fun negativeOrZeroAttemptsUseTheBaseDelay() {
        assertEquals(1_000L, reconnectDelayMillis(-3, randomUnit = 0.0))
    }

    @Test
    fun jitterStaysWithinTheBoundedRange() {
        // 20% jitter at most on top of the base delay.
        assertEquals(1_050L, reconnectDelayMillis(0, randomUnit = 0.25))
        assertEquals(1_200L, reconnectDelayMillis(0, randomUnit = 1.0))
        assertEquals(9_600L, reconnectDelayMillis(3, randomUnit = 1.0))
        for (attempt in 0..8) {
            for (unit in listOf(0.0, 0.3, 0.6, 1.0)) {
                val delay = reconnectDelayMillis(attempt, randomUnit = unit)
                assertTrue("delay $delay must stay positive", delay > 0)
                assertTrue("delay $delay must stay under the cap", delay <= 60_000L)
            }
        }
    }
}
