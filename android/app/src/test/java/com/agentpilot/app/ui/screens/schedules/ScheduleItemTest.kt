package com.agentpilot.app.ui.screens.schedules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * Unit tests for the schedule failure fields and their formatting.
 *
 * Regression context: when a scheduled run failed — "connector is offline",
 * "target no longer exists: session not found" — the reason stayed in the server
 * log, and the row looked exactly like a schedule that simply had not come round
 * yet. The server now carries `lastError` / `lastErrorAt` on every schedule
 * (cleared on a successful run and on every edit) and can switch a schedule off
 * by itself. Both fields are absent on older servers, so parsing them must not
 * fail the whole list, and the failure instant is rendered in the schedule's own
 * timezone — never a hardcoded one.
 */
class ScheduleItemTest {

    private fun schedule(
        id: String = "sch_1",
        title: String = "Morning report",
        scheduleKind: String = "daily",
        timezone: String = "Asia/Shanghai",
        nextRunAt: String? = "2026-10-15T01:00:00Z",
        enabled: Boolean? = true,
        lastError: String? = "connector is offline",
        lastErrorAt: String? = "2026-10-12T00:00:00Z",
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("scheduleKind", scheduleKind)
        put("timezone", timezone)
        nextRunAt?.let { put("nextRunAt", it) }
        enabled?.let { put("enabled", it) }
        lastError?.let { put("lastError", it) }
        lastErrorAt?.let { put("lastErrorAt", it) }
    }

    // --- parsing ----------------------------------------------------------

    @Test
    fun parsesFailureReasonAndWhenItHappened() {
        val item = parseScheduleItem(schedule())

        assertEquals("sch_1", item.id)
        assertEquals("Morning report", item.title)
        assertEquals("daily", item.scheduleKind)
        assertEquals("Asia/Shanghai", item.timezone)
        assertEquals("2026-10-15T01:00:00Z", item.nextRunAt)
        assertEquals("connector is offline", item.lastError)
        assertEquals("2026-10-12T00:00:00Z", item.lastErrorAt)
        assertTrue(item.hasFailure)
    }

    @Test
    fun missingFailureFieldsParseAsNull() {
        // A server predating this field pair sends no `lastError` at all.
        val item = parseScheduleItem(
            schedule(lastError = null, lastErrorAt = null),
        )

        assertNull(item.lastError)
        assertNull(item.lastErrorAt)
        assertFalse(item.hasFailure)
    }

    @Test
    fun explicitNullsParseAsNull() {
        // A successful run (or an edit) clears both fields to JSON null.
        val item = parseScheduleItem(
            JSONObject(
                """
                {
                  "id": "sch_1",
                  "title": "Morning report",
                  "scheduleKind": "daily",
                  "timezone": "UTC",
                  "enabled": true,
                  "nextRunAt": "2026-10-15T01:00:00Z",
                  "lastError": null,
                  "lastErrorAt": null
                }
                """.trimIndent(),
            ),
        )

        assertNull(item.lastError)
        assertNull(item.lastErrorAt)
        assertFalse(item.hasFailure)
    }

    @Test
    fun blankFailureReasonIsTreatedAsNoFailure() {
        val item = parseScheduleItem(schedule(lastError = "   ", lastErrorAt = "2026-10-12T00:00:00Z"))

        assertNull(item.lastError)
        assertFalse(item.hasFailure)
    }

    @Test
    fun serverDisabledScheduleKeepsItsReason() {
        // The server switches a schedule off when its target no longer exists;
        // the row has to say why it stopped.
        val item = parseScheduleItem(
            schedule(
                enabled = false,
                lastError = "target no longer exists: session not found",
                lastErrorAt = "2026-10-12T00:00:00Z",
            ),
        )

        assertFalse(item.enabled)
        assertEquals("target no longer exists: session not found", item.lastError)
        assertTrue(item.hasFailure)
    }

    @Test
    fun enabledDefaultsToTrueWhenAbsent() {
        assertTrue(parseScheduleItem(schedule(enabled = null)).enabled)
        assertFalse(parseScheduleItem(schedule(enabled = false)).enabled)
    }

    @Test
    fun parsesMinimalSchedule() {
        val item = parseScheduleItem(JSONObject().put("id", "sch_2").put("title", "Only a title"))

        assertEquals("sch_2", item.id)
        assertEquals("Only a title", item.title)
        assertEquals("", item.scheduleKind)
        assertNull(item.timezone)
        assertNull(item.nextRunAt)
        assertNull(item.lastError)
        assertNull(item.lastErrorAt)
        assertTrue(item.enabled)
    }

    // --- formatting the failure instant ----------------------------------

    @Test
    fun formatsFailureInTheSchedulesOwnTimezone() {
        // 00:00Z is 08:00 in +08:00 and 20:00 the previous day in New York (EDT).
        assertEquals("10-12 08:00", formatScheduleErrorAt("2026-10-12T00:00:00Z", "Asia/Shanghai"))
        assertEquals("10-11 20:00", formatScheduleErrorAt("2026-10-12T00:00:00Z", "America/New_York"))
        assertEquals("10-12 00:00", formatScheduleErrorAt("2026-10-12T00:00:00Z", "UTC"))
    }

    @Test
    fun blankTimezoneFallsBackToUtcRatherThanAFixedZone() {
        assertEquals("10-12 00:00", formatScheduleErrorAt("2026-10-12T00:00:00Z", ""))
        assertEquals("10-12 00:00", formatScheduleErrorAt("2026-10-12T00:00:00Z", null))
        assertEquals("10-12 00:00", formatScheduleErrorAt("2026-10-12T00:00:00Z", "   "))
    }

    @Test
    fun failureAndNextRunAgreeOnTheZone() {
        val timezone = "America/New_York"
        val nextRun = formatScheduleNextRunAt("2026-10-15T01:00:00Z", timezone)
        val failedAt = formatScheduleErrorAt("2026-10-12T00:00:00Z", timezone)

        assertEquals("10-14 21:00", nextRun)
        assertEquals("10-11 20:00", failedAt)
    }

    @Test
    fun returnsNullForMissingOrUnparseableFailureInstant() {
        assertNull(formatScheduleErrorAt(null, "UTC"))
        assertNull(formatScheduleErrorAt("", "UTC"))
        assertNull(formatScheduleErrorAt("   ", "UTC"))
        assertNull(formatScheduleErrorAt("2026-10-12 00:00", "UTC"))
        assertNull(formatScheduleErrorAt("not-a-timestamp", "UTC"))
    }

    @Test
    fun returnsNullForUnknownTimezone() {
        assertNull(formatScheduleErrorAt("2026-10-12T00:00:00Z", "Mars/Olympus"))
    }

    @Test
    fun acceptsAnOffsetTimestamp() {
        assertEquals("10-12 08:00", formatScheduleErrorAt("2026-10-12T08:00:00+08:00", "Asia/Shanghai"))
    }
}
