package com.agentpilot.app.ui.screens.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpilot.app.R
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.ui.designsystem.LocalAAColors
import com.agentpilot.app.ui.designsystem.noRippleClickable
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DisplayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * Format an instant carried by a schedule for display, in the schedule's own
 * timezone.
 *
 * The timezone comes from the schedule, never a fixed zone: a schedule the user
 * created for `America/New_York` must not be shown in Shanghai time. Returns
 * null when the instant is missing, unparseable, or the zone is unknown.
 */
private fun formatScheduleInstant(instant: String?, timezone: String?): String? {
    if (instant.isNullOrBlank()) return null
    val zoneId = runCatching { ZoneId.of(timezone?.takeIf { it.isNotBlank() } ?: "UTC") }.getOrNull()
        ?: return null
    return runCatching {
        Instant.parse(instant).atZone(zoneId).format(DisplayFormatter)
    }.getOrNull()
}

internal fun formatScheduleNextRunAt(nextRunAt: String?, timezone: String?): String? =
    formatScheduleInstant(nextRunAt, timezone)

/**
 * Format `lastErrorAt` — when a schedule last failed — in the schedule's own
 * timezone, the same rule as [formatScheduleNextRunAt]. A failure time shown in
 * a zone the schedule was never created for is off by hours, which is exactly
 * the confusion the timestamp was added to remove.
 */
internal fun formatScheduleErrorAt(lastErrorAt: String?, timezone: String?): String? =
    formatScheduleInstant(lastErrorAt, timezone)

/**
 * Parse a user-typed local wall-clock time into the UTC instant the server wants.
 *
 * The typed value is read in `zoneId` — the zone the user is actually in — and
 * the same zone id is sent to the server alongside it, so the schedule and its
 * display agree. Reading a typed time in a zone other than the user's is what
 * silently shifts a one-shot schedule by hours.
 */
internal fun parseLocalScheduleTime(dateText: String, zoneId: java.time.ZoneId): String? {
    return runCatching {
        java.time.LocalDateTime.parse(dateText.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            .atZone(zoneId)
            .toInstant()
            .toString()
    }.getOrNull()
}

@Composable
internal fun SchedulesScreen(
    onBack: () -> Unit,
    sessions: List<AgentSession>,
    onListSchedules: suspend () -> Result<JSONArray>,
    onCreateSchedule: suspend (
        title: String,
        prompt: String,
        scheduleKind: String,
        runAt: String?,
        runTime: String?,
        weekday: Int?,
        timezone: String,
        sessionId: String?,
    ) -> Result<JSONObject>,
    onDeleteSchedule: suspend (String) -> Result<Unit>,
    onSetScheduleEnabled: suspend (String, Boolean) -> Result<JSONObject>,
) {
    val colors = LocalAAColors.current
    val scope = rememberCoroutineScope()
    var schedules by remember { mutableStateOf<List<ScheduleItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ScheduleItem?>(null) }

    fun refresh() {
        scope.launch {
            onListSchedules()
                .onSuccess { array ->
                    // The call is already IO-bound; parsing the org.json rows is
                    // pure CPU work, so keep it off the main thread as well.
                    schedules = withContext(Dispatchers.Default) {
                        (0 until array.length()).map { parseScheduleItem(array.getJSONObject(it)) }
                    }
                    errorMessage = null
                }
                .onFailure { error -> errorMessage = error.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val titleText = stringResource(R.string.schedules_title)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .noRippleClickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.ChevronLeft, stringResource(R.string.common_back), tint = colors.ink, modifier = Modifier.size(22.dp))
            }
            Text(
                text = titleText,
                color = colors.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.primaryAction)
                    .noRippleClickable(onClick = { createError = null; createOpen = true })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Plus, null, tint = colors.onPrimaryAction, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.schedules_new),
                    color = colors.onPrimaryAction,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.inkSoft, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            errorMessage != null -> Text(
                text = errorMessage ?: "",
                color = colors.errorText,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
            schedules.isEmpty() -> Text(
                text = stringResource(R.string.schedules_empty),
                color = colors.muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 24.dp, start = 4.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 32.dp),
            ) {
                items(schedules, key = { it.id }) { item ->
                    ScheduleRow(
                        item = item,
                        onToggle = { enabled ->
                            scope.launch {
                                onSetScheduleEnabled(item.id, enabled)
                                    .onSuccess { refresh() }
                            }
                        },
                        onDelete = { deleteTarget = item },
                    )
                }
            }
        }
    }

    if (createOpen) {
        ScheduleCreateDialog(
            sessions = sessions,
            saving = false,
            serverError = createError,
            onCreate = { title, prompt, kind, runAt, runTime, weekday, sessionId, timezone ->
                scope.launch {
                    onCreateSchedule(title, prompt, kind, runAt, runTime, weekday, timezone, sessionId)
                        .onSuccess {
                            createOpen = false
                            createError = null
                            refresh()
                        }
                        .onFailure { error -> createError = error.message ?: error.toString() }
                }
            },
            onCancel = { createOpen = false },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = colors.raisedSurface,
            title = { Text(stringResource(R.string.schedules_delete), color = colors.ink) },
            text = { Text(target.title, color = colors.inkSoft) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        onDeleteSchedule(target.id)
                        deleteTarget = null
                        refresh()
                    }
                }) { Text(stringResource(R.string.schedules_delete), color = colors.errorText) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.muted)
                }
            },
        )
    }
}

@Composable
private fun ScheduleRow(
    item: ScheduleItem,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalAAColors.current
    val kindLabel = when (item.scheduleKind) {
        "once" -> stringResource(R.string.schedules_kind_once)
        "weekly" -> stringResource(R.string.schedules_kind_weekly)
        else -> stringResource(R.string.schedules_kind_daily)
    }
    val nextText = remember(item.nextRunAt, item.timezone) {
        formatScheduleNextRunAt(item.nextRunAt, item.timezone)
    }
    // Why this schedule last failed, and when. Nothing is rendered while the
    // server has no failure to report (`lastError` is cleared on a successful
    // run and on every edit).
    val failureWhenText = formatScheduleErrorAt(item.lastErrorAt, item.timezone)
    val failureText = item.lastError?.let { reason ->
        if (failureWhenText != null) {
            stringResource(R.string.schedules_last_error_at, reason, failureWhenText)
        } else {
            stringResource(R.string.schedules_last_error, reason)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.subtle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = colors.ink,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(kindLabel, nextText).joinToString(" · "),
                color = colors.muted,
                fontSize = 12.sp,
                maxLines = 1,
            )
            failureText?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = it,
                    color = colors.errorText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = item.enabled,
            onCheckedChange = onToggle,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Lucide.Trash2,
            contentDescription = stringResource(R.string.schedules_delete),
            tint = colors.errorText,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .noRippleClickable(onClick = onDelete)
                .padding(7.dp),
        )
    }
}

@Composable
private fun ScheduleCreateDialog(
    sessions: List<AgentSession>,
    saving: Boolean,
    serverError: String?,
    onCreate: (
        title: String,
        prompt: String,
        kind: String,
        runAt: String?,
        runTime: String?,
        weekday: Int?,
        sessionId: String?,
        timezone: String,
    ) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalAAColors.current
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("daily") }
    var timeText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var weekdayIndex by remember { mutableStateOf(0) }
    var targetSessionId by remember { mutableStateOf(sessions.firstOrNull()?.id) }
    var error by remember { mutableStateOf<String?>(null) }
    val labelColor = colors.inkSoft
    val weekdayLabels = listOf(
        stringResource(R.string.schedules_weekday_1), stringResource(R.string.schedules_weekday_2),
        stringResource(R.string.schedules_weekday_3), stringResource(R.string.schedules_weekday_4),
        stringResource(R.string.schedules_weekday_5), stringResource(R.string.schedules_weekday_6),
        stringResource(R.string.schedules_weekday_7),
    )
    val invalidTimeText = stringResource(R.string.schedules_invalid_time)
    val requiredText = stringResource(R.string.schedules_required)

    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        containerColor = colors.raisedSurface,
        title = { Text(stringResource(R.string.schedules_new), color = colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldValue(stringResource(R.string.schedules_title_label), title, labelColor) { title = it }
                FieldValue(stringResource(R.string.schedules_prompt_label), prompt, labelColor) { prompt = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KindChip("once", kind, labelColor) { kind = "once" }
                    KindChip("daily", kind, labelColor) { kind = "daily" }
                    KindChip("weekly", kind, labelColor) { kind = "weekly" }
                }
                if (kind == "once") {
                    FieldValue(stringResource(R.string.schedules_time_label), dateText, labelColor) { dateText = it }
                } else {
                    FieldValue(stringResource(R.string.schedules_time_label), timeText, labelColor) { timeText = it }
                }
                if (kind == "weekly") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        weekdayLabels.forEachIndexed { index, label ->
                            WeekChip(label, weekdayIndex == index, labelColor) { weekdayIndex = index }
                        }
                    }
                }
                Text(stringResource(R.string.schedules_target), color = colors.muted, fontSize = 12.sp)
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.height(120.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (session.id == targetSessionId) colors.subtle else Color.Transparent,
                                )
                                .noRippleClickable(onClick = { targetSessionId = session.id })
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = session.title.ifBlank { session.runtime },
                                color = colors.ink,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                (error ?: serverError)?.let { Text(it, color = colors.errorText, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    // The user types a local wall-clock time, so it is read in
                    // the device's own zone and that zone is sent along. A fixed
                    // zone here would silently shift every one-shot schedule for
                    // anyone outside it.
                    val zone = ZoneId.systemDefault()
                    val runAt = if (kind == "once") parseLocalScheduleTime(dateText, zone) else null
                    val runTime = if (kind == "once") null else timeText.trim()
                    when {
                        title.isBlank() -> error = requiredText
                        prompt.isBlank() -> error = requiredText
                        targetSessionId == null -> error = requiredText
                        kind == "once" && runAt == null ->
                            error = invalidTimeText
                        kind != "once" && !Regex("^\\d{1,2}:\\d{2}$").matches(runTime.orEmpty()) ->
                            error = invalidTimeText
                        else -> onCreate(
                            title.trim(), prompt.trim(), kind, runAt, runTime,
                            if (kind == "weekly") weekdayIndex else null, targetSessionId, zone.id,
                        )
                    }
                },
            ) { Text(stringResource(R.string.schedules_create), color = colors.primaryAction) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel), color = colors.muted) }
        },
    )
}

@Composable
private fun FieldValue(label: String, value: String, labelColor: Color, onChange: (String) -> Unit) {
    val colors = LocalAAColors.current
    Column {
        Text(label, color = colors.muted, fontSize = 11.5.sp)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = label.length < 12,
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink, fontSize = 14.sp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (colors.isDark) colors.canvas else Color(0xFFF1F0ED))
                .padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun KindChip(kind: String, current: String, labelColor: Color, onSelect: () -> Unit) {
    val colors = LocalAAColors.current
    val label = when (kind) {
        "once" -> stringResource(R.string.schedules_kind_once)
        "weekly" -> stringResource(R.string.schedules_kind_weekly)
        else -> stringResource(R.string.schedules_kind_daily)
    }
    val selected = kind == current
    Text(
        text = label,
        color = if (selected) colors.onPrimaryAction else labelColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.primaryAction else colors.subtle)
            .noRippleClickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun WeekChip(label: String, selected: Boolean, labelColor: Color, onSelect: () -> Unit) {
    val colors = LocalAAColors.current
    Text(
        text = label,
        color = if (selected) colors.onPrimaryAction else labelColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.primaryAction else colors.subtle)
            .noRippleClickable(onClick = onSelect)
            .padding(horizontal = 7.dp, vertical = 6.dp),
    )
}
