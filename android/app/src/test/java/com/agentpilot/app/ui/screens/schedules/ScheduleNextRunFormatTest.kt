package com.agentpilot.app.ui.screens.schedules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for schedule next-run formatting.
 *
 * Regression context: the row used to render `nextRunAt` in a hardcoded
 * `Asia/Shanghai` zone. A schedule created for `America/New_York` therefore
 * showed a time 12 hours off from what the user asked for, and there was no way
 * to tell from the UI that the schedule itself was correct. The zone now comes
 * from the schedule.
 */
class ScheduleNextRunFormatTest {

    @Test
    fun rendersInTheSchedulesOwnTimezone() {
        // 01:30Z is 09:30 in +08:00 and 21:30 the previous day in New York (EDT, -04:00).
        assertEquals("10-20 09:30", formatScheduleNextRunAt("2026-10-20T01:30:00Z", "Asia/Shanghai"))
        assertEquals("10-19 21:30", formatScheduleNextRunAt("2026-10-20T01:30:00Z", "America/New_York"))
        assertEquals("10-20 01:30", formatScheduleNextRunAt("2026-10-20T01:30:00Z", "UTC"))
    }

    @Test
    fun blankTimezoneFallsBackToUtcRatherThanAFixedZone() {
        assertEquals("10-20 01:30", formatScheduleNextRunAt("2026-10-20T01:30:00Z", ""))
        assertEquals("10-20 01:30", formatScheduleNextRunAt("2026-10-20T01:30:00Z", null))
        assertEquals("10-20 01:30", formatScheduleNextRunAt("2026-10-20T01:30:00Z", "   "))
    }

    @Test
    fun returnsNullForMissingOrUnparseableInput() {
        assertNull(formatScheduleNextRunAt(null, "UTC"))
        assertNull(formatScheduleNextRunAt("", "UTC"))
        assertNull(formatScheduleNextRunAt("   ", "UTC"))
        assertNull(formatScheduleNextRunAt("not-a-timestamp", "UTC"))
    }

    @Test
    fun returnsNullForUnknownTimezone() {
        assertNull(formatScheduleNextRunAt("2026-10-20T01:30:00Z", "Mars/Olympus"))
    }

    @Test
    fun acceptsAnOffsetTimestamp() {
        // The server sends `Z`, but a `+08:00` form must not be rejected.
        assertEquals("10-20 09:30", formatScheduleNextRunAt("2026-10-20T09:30:00+08:00", "Asia/Shanghai"))
    }

    @Test
    fun localScheduleTimeIsReadInTheGivenZone() {
        // Regression context: the create dialog read the typed time in a
        // hardcoded Asia/Shanghai, so a user anywhere else got a schedule hours
        // off from what they typed. The zone is now the caller's, and the same
        // id is sent to the server so display and trigger agree.
        assertEquals(
            "2026-10-20T01:30:00Z",
            parseLocalScheduleTime("2026-10-20 09:30", java.time.ZoneId.of("Asia/Shanghai")),
        )
        assertEquals(
            "2026-10-20T13:30:00Z",
            parseLocalScheduleTime("2026-10-20 09:30", java.time.ZoneId.of("America/New_York")),
        )
        assertEquals(
            "2026-10-20T09:30:00Z",
            parseLocalScheduleTime("2026-10-20 09:30", java.time.ZoneId.of("UTC")),
        )
    }

    @Test
    fun localScheduleTimeRejectsMalformedInput() {
        assertNull(parseLocalScheduleTime("", java.time.ZoneId.of("UTC")))
        assertNull(parseLocalScheduleTime("2026-10-20", java.time.ZoneId.of("UTC")))
        assertNull(parseLocalScheduleTime("10-20 09:30", java.time.ZoneId.of("UTC")))
        assertNull(parseLocalScheduleTime("2026-13-40 99:99", java.time.ZoneId.of("UTC")))
    }

    @Test
    fun localScheduleTimeRoundTripsThroughDisplay() {
        // What the dialog produces must render back to the same wall clock.
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val instant = parseLocalScheduleTime("2026-10-20 09:30", zone)
        assertEquals("10-20 09:30", formatScheduleNextRunAt(instant, zone.id))
    }
}
