package com.agentpilot.app.ui.screens.schedules

import com.agentpilot.app.api.optNullableString
import org.json.JSONObject

/**
 * One schedule as `GET /api/v2/schedules` reports it.
 *
 * [lastError] / [lastErrorAt] are why a schedule last failed and when. The
 * server clears both on a successful run and on every edit, and can switch a
 * schedule off by itself when its target no longer exists — the row has to show
 * that reason, otherwise a schedule that silently stopped running looks exactly
 * like one that simply has not come round yet.
 *
 * Servers older than this field pair omit them entirely, so absent and explicit
 * `null` both parse as null rather than failing the whole list.
 */
internal data class ScheduleItem(
    val id: String,
    val title: String,
    val scheduleKind: String,
    val enabled: Boolean,
    val timezone: String?,
    val nextRunAt: String?,
    val lastError: String?,
    val lastErrorAt: String?,
) {
    /** True when the row should explain a failed run. */
    val hasFailure: Boolean get() = !lastError.isNullOrBlank()
}

internal fun parseScheduleItem(json: JSONObject): ScheduleItem = ScheduleItem(
    id = json.optString("id"),
    title = json.optString("title"),
    scheduleKind = json.optString("scheduleKind"),
    enabled = json.optBoolean("enabled", true),
    timezone = json.optNullableString("timezone"),
    nextRunAt = json.optNullableString("nextRunAt"),
    lastError = json.optNullableString("lastError"),
    lastErrorAt = json.optNullableString("lastErrorAt"),
)
