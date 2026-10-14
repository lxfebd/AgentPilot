import assert from "node:assert/strict"
import test from "node:test"

import {
  WEEKDAY_LABELS,
  browserTimezone,
  buildScheduleCreateRequest,
  buildScheduleEnabledPatch,
  buildScheduleTarget,
  deriveScheduleFailure,
  emptyScheduleDraft,
  formatInstantLocal,
  formatNextRun,
  formatTriggerSummary,
  isFutureInstant,
  isValidRunTime,
  isValidWeekday,
  parseLocalDateTime,
  scheduleHasFinished,
  scheduleTargetError,
  sortSchedules,
  toDateTimeLocalValue,
  validateScheduleForm,
  weekdayName,
} from "../src/features/schedules/schedule-presentation.ts"

const NOW = new Date("2026-10-14T02:00:00.000Z")

function schedule(overrides = {}) {
  return {
    id: "sched_1",
    title: "Daily digest",
    prompt: "Summarize the repo",
    scheduleKind: "daily",
    runAt: null,
    runTime: "09:30",
    weekday: null,
    timezone: "Asia/Shanghai",
    enabled: true,
    sessionId: "sess_1",
    projectId: null,
    connectorId: null,
    runtime: null,
    cwd: null,
    nextRunAt: "2026-10-15T01:30:00.000Z",
    lastRunAt: null,
    lastError: null,
    lastErrorAt: null,
    createdAt: "2026-10-01T00:00:00.000Z",
    updatedAt: "2026-10-01T00:00:00.000Z",
    ...overrides,
  }
}

function draft(overrides = {}) {
  return {
    ...emptyScheduleDraft("Asia/Shanghai"),
    title: "Daily digest",
    prompt: "Summarize the repo",
    runTime: "09:30",
    sessionId: "sess_1",
    projectId: null,
    connectorId: null,
    ...overrides,
  }
}

// ── Timezone ────────────────────────────────────────────────────

test("the draft timezone is the browser zone and never a hardcoded one", () => {
  assert.equal(emptyScheduleDraft().timezone, browserTimezone())
  assert.equal(emptyScheduleDraft("Europe/Berlin").timezone, "Europe/Berlin")
  assert.ok(browserTimezone().length > 0)
})

// ── runTime / weekday shapes ────────────────────────────────────

test("runTime accepts 24-hour HH:MM and rejects every other shape", () => {
  for (const value of ["00:00", "09:30", "23:59", " 09:30 "]) {
    assert.equal(isValidRunTime(value), true, value)
  }
  for (const value of ["9:30", "24:00", "09:60", "09", "", "   ", "09:30:00", "930", null, undefined]) {
    assert.equal(isValidRunTime(value), false, String(value))
  }
})

test("weekday is 0 (Monday) through 6 (Sunday) and nothing else", () => {
  for (const value of [0, 1, 2, 3, 4, 5, 6]) {
    assert.equal(isValidWeekday(value), true, String(value))
  }
  for (const value of [-1, 7, 1.5, "1", null, undefined]) {
    assert.equal(isValidWeekday(value), false, String(value))
  }
})

test("weekday names start at Monday so index 0 matches the server contract", () => {
  assert.equal(WEEKDAY_LABELS[0], "Monday")
  assert.equal(WEEKDAY_LABELS[6], "Sunday")
  assert.equal(weekdayName(0), "Monday")
  assert.equal(weekdayName(6), "Sunday")
  assert.equal(weekdayName(7), null)
  assert.equal(weekdayName(null), null)
})

// ── Instant ↔ wall clock ────────────────────────────────────────

test("a wall clock is interpreted in the schedule's own timezone", () => {
  // 09:30 in Asia/Shanghai (UTC+8) is 01:30 UTC, including across DST edges.
  assert.equal(parseLocalDateTime("2026-10-15T09:30", "Asia/Shanghai")?.toISOString(), "2026-10-15T01:30:00.000Z")
  assert.equal(parseLocalDateTime("2026-10-15T09:30", "UTC")?.toISOString(), "2026-10-15T09:30:00.000Z")
  assert.equal(parseLocalDateTime("2026-10-15T09:30", "America/New_York")?.toISOString(), "2026-10-15T13:30:00.000Z")
  // Summer time, not standard time: New York is UTC-4 in July.
  assert.equal(parseLocalDateTime("2026-07-15T09:30", "America/New_York")?.toISOString(), "2026-07-15T13:30:00.000Z")
  assert.equal(parseLocalDateTime("2026-01-15T09:30", "America/New_York")?.toISOString(), "2026-01-15T14:30:00.000Z")
})

test("a malformed wall clock, an unknown zone or a DST gap yields no instant", () => {
  for (const value of [null, "", "   ", "tomorrow", "2026-10-15", "2026-13-40T09:30", "2026-10-15 25:00"]) {
    assert.equal(parseLocalDateTime(value, "Asia/Shanghai"), null, String(value))
  }
  assert.equal(parseLocalDateTime("2026-10-15T09:30", "Not/AZone"), null)
  // 02:30 does not exist on the spring-forward day in New York.
  assert.equal(parseLocalDateTime("2026-03-08T02:30", "America/New_York"), null)
})

test("round-tripping a wall clock through UTC keeps the same local value", () => {
  for (const zone of ["UTC", "Asia/Shanghai", "America/New_York", "Europe/Berlin", "Australia/Sydney"]) {
    const instant = parseLocalDateTime("2026-07-20T14:05", zone)
    assert.ok(instant, zone)
    assert.equal(toDateTimeLocalValue(instant.toISOString(), zone), "2026-07-20T14:05", zone)
  }
})

test("a UTC instant is only future when strictly ahead of now", () => {
  assert.equal(isFutureInstant("2026-10-14T02:00:00.001Z", NOW), true)
  assert.equal(isFutureInstant("2026-10-14T02:00:00.000Z", NOW), false)
  assert.equal(isFutureInstant("2026-10-14T01:59:59.999Z", NOW), false)
  assert.equal(isFutureInstant(null, NOW), false)
  assert.equal(isFutureInstant("not-a-date", NOW), false)
})

test("instants render in the schedule timezone, not the browser one", () => {
  assert.equal(formatInstantLocal("2026-10-15T01:30:00.000Z", "Asia/Shanghai"), "2026-10-15 09:30")
  assert.equal(formatInstantLocal("2026-10-15T01:30:00.000Z", "UTC"), "2026-10-15 01:30")
  assert.equal(formatInstantLocal(null, "Asia/Shanghai"), null)
})

// ── Trigger summary ─────────────────────────────────────────────

const SUMMARY_LABELS = {
  once: "Once",
  daily: "Every day",
  weekly: "Every {weekday}",
  weekdays: ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
  timezoneSuffix: (zone) => `(${zone})`,
}

test("the trigger summary names the weekday, time and timezone", () => {
  assert.equal(
    formatTriggerSummary(schedule({ scheduleKind: "weekly", weekday: 0, runTime: "09:30" }), SUMMARY_LABELS),
    "Every Monday 09:30 (Asia/Shanghai)",
  )
  assert.equal(
    formatTriggerSummary(schedule({ scheduleKind: "weekly", weekday: 6, runTime: "23:59" }), SUMMARY_LABELS),
    "Every Sunday 23:59 (Asia/Shanghai)",
  )
  assert.equal(
    formatTriggerSummary(schedule({ scheduleKind: "daily", runTime: "06:00", timezone: "Europe/Berlin" }), SUMMARY_LABELS),
    "Every day 06:00 (Europe/Berlin)",
  )
})

test("a one-shot summary shows the local runAt, and never a weekday", () => {
  assert.equal(
    formatTriggerSummary(schedule({
      scheduleKind: "once",
      runAt: "2026-10-20T01:00:00.000Z",
      runTime: null,
      weekday: null,
    }), SUMMARY_LABELS),
    "Once, 2026-10-20 09:00 (Asia/Shanghai)",
  )
  assert.equal(
    formatTriggerSummary(schedule({
      scheduleKind: "once",
      runAt: "2026-10-20T01:00:00.000Z",
      runTime: null,
      weekday: 3,
      timezone: "UTC",
    }), SUMMARY_LABELS),
    "Once, 2026-10-20 01:00 (UTC)",
  )
  // No runAt and no nextRunAt: just the label, no dangling separator.
  assert.equal(
    formatTriggerSummary(schedule({ scheduleKind: "once", runAt: null, nextRunAt: null }), SUMMARY_LABELS),
    "Once (Asia/Shanghai)",
  )
})

test("a recurring schedule with no usable runTime cannot be summarised", () => {
  assert.equal(formatTriggerSummary(schedule({ scheduleKind: "daily", runTime: null }), SUMMARY_LABELS), "—")
  assert.equal(formatTriggerSummary(schedule({ scheduleKind: "daily", runTime: "9:5" }), SUMMARY_LABELS), "—")
  assert.equal(formatTriggerSummary(schedule({ scheduleKind: "weekly", weekday: 9, runTime: "09:30" }), SUMMARY_LABELS), "—")
})

test("the one-shot summary shape matches the Web and iOS clients", () => {
  // Only the leading label is comma-separated; the timezone stays attached to
  // the timestamp. A timezone name must never be mistaken for a separator.
  const oddLabels = { ...SUMMARY_LABELS, timezoneSuffix: (zone) => `(${zone})` }
  assert.equal(
    formatTriggerSummary(schedule({
      scheduleKind: "once",
      runAt: "2026-10-20T01:00:00.000Z",
      timezone: "UTC",
      runTime: null,
    }), oddLabels),
    "Once, 2026-10-20 01:00 (UTC)",
  )
})

// ── Next run ────────────────────────────────────────────────────

test("next run shows the next trigger, and nothing once a one-shot has fired", () => {
  assert.equal(formatNextRun(schedule(), "Asia/Shanghai"), "2026-10-15 09:30")
  assert.equal(formatNextRun(schedule({ nextRunAt: null }), "Asia/Shanghai"), null)
  assert.equal(scheduleHasFinished(schedule({ nextRunAt: null })), false)
  assert.equal(scheduleHasFinished(schedule({ scheduleKind: "once", nextRunAt: null })), true)
  assert.equal(scheduleHasFinished(schedule({ scheduleKind: "once", nextRunAt: "2026-10-15T01:30:00.000Z" })), false)
  assert.equal(scheduleHasFinished(schedule({ scheduleKind: "weekly", nextRunAt: null })), false)
})

// ── Failed runs ─────────────────────────────────────────────────

test("a schedule with no reported failure shows nothing extra", () => {
  assert.equal(deriveScheduleFailure(schedule()), null)
  assert.equal(deriveScheduleFailure(schedule({ lastError: null, lastErrorAt: null })), null)
  // A blank reason is nothing the user can act on, and a timestamp on its own
  // is not a failure.
  assert.equal(deriveScheduleFailure(schedule({ lastError: "", lastErrorAt: "2026-10-12T00:00:00Z" })), null)
  assert.equal(deriveScheduleFailure(schedule({ lastError: "   ", lastErrorAt: null })), null)
  assert.equal(deriveScheduleFailure(schedule({ lastError: null, lastErrorAt: "2026-10-12T00:00:00Z" })), null)
  // A schedule the user paused is not a failure, and stays unreported.
  assert.equal(deriveScheduleFailure(schedule({ enabled: false })), null)
})

test("a reported failure carries the reason and the attempt time in the schedule's own timezone", () => {
  const failure = deriveScheduleFailure(schedule({
    lastError: "connector is offline",
    lastErrorAt: "2026-10-12T00:00:00Z",
  }))
  assert.deepEqual(failure, {
    reason: "connector is offline",
    // 00:00 UTC reads as 08:00 in the schedule's zone, exactly like the next run.
    at: "2026-10-12 08:00",
    switchedOff: false,
  })
  // The zone belongs to the schedule, never to the machine running the test.
  for (const [zone, expected] of [
    ["UTC", "2026-10-12 00:00"],
    ["America/New_York", "2026-10-11 20:00"],
    ["Europe/Berlin", "2026-10-12 02:00"],
  ]) {
    const inZone = deriveScheduleFailure(
      schedule({ timezone: zone, lastError: "connector is offline", lastErrorAt: "2026-10-12T00:00:00Z" }),
    )
    assert.equal(inZone?.at, expected, zone)
  }
  // The reason is the server's own wording, minus surrounding whitespace.
  assert.equal(deriveScheduleFailure(schedule({ lastError: "  connector is offline  " }))?.reason, "connector is offline")
})

test("a failure with no usable timestamp still reports the reason", () => {
  assert.deepEqual(
    deriveScheduleFailure(schedule({ lastError: "connector is offline", lastErrorAt: null })),
    { reason: "connector is offline", at: null, switchedOff: false },
  )
  const broken = deriveScheduleFailure(schedule({ lastError: "connector is offline", lastErrorAt: "sometime" }))
  assert.equal(broken?.reason, "connector is offline")
  assert.equal(broken?.at, null)
  // An unusable zone name falls back to the raw instant instead of guessing.
  assert.equal(
    deriveScheduleFailure(schedule({ timezone: "Not/AZone", lastError: "boom", lastErrorAt: "2026-10-12T00:00:00Z" }))?.at,
    "2026-10-12T00:00:00.000Z",
  )
  // A missing zone name falls back to UTC, the same rule the next run uses.
  assert.equal(
    deriveScheduleFailure(schedule({ timezone: "  ", lastError: "boom", lastErrorAt: "2026-10-12T00:00:00Z" }))?.at,
    "2026-10-12 00:00",
  )
})

test("a schedule the server switched off reports the off state together with its reason", () => {
  // What the server writes when the target is gone: enabled false, no next
  // trigger, and the reason on the schedule itself.
  const stopped = schedule({
    scheduleKind: "once",
    enabled: false,
    nextRunAt: null,
    lastError: "target no longer exists: session not found",
    lastErrorAt: "2026-10-12T00:00:00Z",
  })
  const failure = deriveScheduleFailure(stopped)
  assert.deepEqual(failure, {
    reason: "target no longer exists: session not found",
    at: "2026-10-12 08:00",
    switchedOff: true,
  })
  // The trigger state alone would read as a one-shot that finished; the failure
  // is what tells the row to say "off, because the target is gone" instead.
  assert.equal(scheduleHasFinished(stopped), true)
  assert.equal(failure.switchedOff, true)
})

test("deriving a failure is pure: same input, same answer, no mutation", () => {
  const input = schedule({ lastError: "connector is offline", lastErrorAt: "2026-10-12T00:00:00Z" })
  const copy = { ...input }
  assert.deepEqual(deriveScheduleFailure(input), deriveScheduleFailure(input))
  assert.deepEqual(input, copy)
})

// ── Target selection ────────────────────────────────────────────

test("exactly one target is allowed: a session, or a project plus connector", () => {
  assert.equal(scheduleTargetError({ sessionId: "s1", projectId: null, connectorId: null }), null)
  assert.equal(scheduleTargetError({ sessionId: null, projectId: "p1", connectorId: "c1" }), null)
  assert.equal(scheduleTargetError({ sessionId: null, projectId: null, connectorId: null }), "targetRequired")
  assert.equal(scheduleTargetError({ sessionId: "s1", projectId: "p1", connectorId: "c1" }), "targetRequired")
  assert.equal(scheduleTargetError({ sessionId: "s1", projectId: null, connectorId: "c1" }), "targetRequired")
  assert.equal(scheduleTargetError({ sessionId: "s1", projectId: "p1", connectorId: null }), "targetRequired")
  // A half-filled new-session target is as incomplete as an empty one.
  assert.equal(scheduleTargetError({ sessionId: null, projectId: "p1", connectorId: null }), "targetRequired")
  assert.equal(scheduleTargetError({ sessionId: null, projectId: null, connectorId: "c1" }), "targetRequired")
})

test("the target tab is explicit state, not derived from the id fields", () => {
  // Clearing the picked project must leave the form on the project tab. A
  // derived tab would silently flip back to the session picker mid-edit.
  const projectTab = { ...draft(), targetMode: "project", projectId: "p1", connectorId: "c1" }
  assert.equal(projectTab.targetMode, "project")
  const cleared = { ...projectTab, projectId: null }
  assert.equal(cleared.targetMode, "project")
  // Validation still reports the incomplete target rather than accepting it.
  assert.equal(scheduleTargetError(cleared), "targetRequired")

  // The empty draft starts on the session tab.
  assert.equal(emptyScheduleDraft("UTC").targetMode, "session")
})

// ── Create-form validation ──────────────────────────────────────

test("a valid daily form has no errors", () => {
  assert.deepEqual(validateScheduleForm(draft(), NOW), {})
})

test("title and prompt must both be non-empty", () => {
  assert.equal(validateScheduleForm(draft({ title: "   " }), NOW).title, "titleRequired")
  assert.equal(validateScheduleForm(draft({ title: "" }), NOW).title, "titleRequired")
  assert.equal(validateScheduleForm(draft({ prompt: "  \n " }), NOW).prompt, "promptRequired")
  assert.equal(validateScheduleForm(draft({ title: "ok", prompt: "ok" }), NOW).title, undefined)
  assert.equal(validateScheduleForm(draft({ title: "ok", prompt: "ok" }), NOW).prompt, undefined)
})

test("once requires a future runAt in the form's own timezone", () => {
  const future = draft({ scheduleKind: "once", runAt: "2026-10-15T09:30", runTime: null, weekday: null })
  assert.equal(validateScheduleForm(future, NOW).runAt, undefined)

  const past = draft({ scheduleKind: "once", runAt: "2026-10-13T09:30", runTime: null, weekday: null })
  assert.equal(validateScheduleForm(past, NOW).runAt, "runAtMustBeFuture")

  // Exactly now is not "in the future".
  const exactlyNow = draft({ scheduleKind: "once", runAt: "2026-10-14T10:00", runTime: null, weekday: null })
  assert.equal(validateScheduleForm(exactlyNow, NOW).runAt, "runAtMustBeFuture")

  const missing = draft({ scheduleKind: "once", runAt: null, runTime: null, weekday: null })
  assert.equal(validateScheduleForm(missing, NOW).runAt, "runAtRequired")

  const garbage = draft({ scheduleKind: "once", runAt: "next tuesday", runTime: null, weekday: null })
  assert.equal(validateScheduleForm(garbage, NOW).runAt, "runAtRequired")
})

test("once never asks for runTime or a weekday", () => {
  const errors = validateScheduleForm(draft({ scheduleKind: "once", runAt: "2026-10-15T09:30", runTime: "9:5", weekday: 9 }), NOW)
  assert.equal(errors.runTime, undefined)
  assert.equal(errors.weekday, undefined)
})

test("daily and weekly both require HH:MM", () => {
  for (const kind of ["daily", "weekly"]) {
    const bad = draft({ scheduleKind: kind, runTime: "9:30" })
    assert.equal(validateScheduleForm(bad, NOW).runTime, "runTimeRequired", kind)
    const missing = draft({ scheduleKind: kind, runTime: null })
    assert.equal(validateScheduleForm(missing, NOW).runTime, "runTimeRequired", kind)
  }
})

test("only weekly requires a weekday, and it must be 0-6", () => {
  const daily = draft({ scheduleKind: "daily", weekday: 99 })
  assert.equal(validateScheduleForm(daily, NOW).weekday, undefined)

  const weeklyMissing = draft({ scheduleKind: "weekly", weekday: null })
  assert.equal(validateScheduleForm(weeklyMissing, NOW).weekday, "weekdayRequired")

  const weeklyBad = draft({ scheduleKind: "weekly", weekday: 7 })
  assert.equal(validateScheduleForm(weeklyBad, NOW).weekday, "weekdayRequired")

  const weeklyGood = draft({ scheduleKind: "weekly", weekday: 2 })
  assert.deepEqual(validateScheduleForm(weeklyGood, NOW), {})
})

test("every violation is reported at once so the form can show them together", () => {
  const errors = validateScheduleForm(
    draft({ title: "", prompt: "", scheduleKind: "weekly", runTime: "99:99", weekday: 12, sessionId: null, projectId: "p1", connectorId: null }),
    NOW,
  )
  assert.deepEqual(Object.keys(errors).sort(), ["prompt", "runTime", "target", "title", "weekday"])
})

// ── Request bodies ──────────────────────────────────────────────

test("the create body keeps only the fields the chosen kind uses", () => {
  const once = buildScheduleCreateRequest(draft({
    scheduleKind: "once",
    runAt: "2026-10-15T09:30",
    runTime: "07:00",
    weekday: 5,
  }))
  assert.deepEqual(once, {
    title: "Daily digest",
    prompt: "Summarize the repo",
    scheduleKind: "once",
    timezone: "Asia/Shanghai",
    sessionId: "sess_1",
    runAt: "2026-10-15T01:30:00.000Z",
  })
  assert.equal("runTime" in once, false)
  assert.equal("weekday" in once, false)

  const weekly = buildScheduleCreateRequest(draft({ scheduleKind: "weekly", runTime: " 09:30 ", weekday: 1 }))
  assert.deepEqual(weekly, {
    title: "Daily digest",
    prompt: "Summarize the repo",
    scheduleKind: "weekly",
    timezone: "Asia/Shanghai",
    sessionId: "sess_1",
    runTime: "09:30",
    weekday: 1,
  })
  assert.equal("runAt" in weekly, false)

  const daily = buildScheduleCreateRequest(draft({ scheduleKind: "daily", runTime: "09:30" }))
  assert.equal("weekday" in daily, false)
  assert.equal("runAt" in daily, false)
})

test("a new-session target sends projectId and connectorId, never a sessionId", () => {
  assert.deepEqual(buildScheduleTarget({ sessionId: null, projectId: "p1", connectorId: "c1" }), { projectId: "p1", connectorId: "c1" })
  assert.deepEqual(buildScheduleTarget({ sessionId: " s1 ", projectId: null, connectorId: null }), { sessionId: "s1" })
})

test("the enable/disable patch carries only the flag", () => {
  assert.deepEqual(buildScheduleEnabledPatch(false), { enabled: false })
  assert.deepEqual(buildScheduleEnabledPatch(true), { enabled: true })
})

// ── Listing order ───────────────────────────────────────────────

test("schedules sort by next trigger, then by recency, then by id", () => {
  const later = schedule({ id: "b", nextRunAt: "2026-10-20T01:30:00.000Z" })
  const sooner = schedule({ id: "c", nextRunAt: "2026-10-15T01:30:00.000Z" })
  const never = schedule({ id: "a", nextRunAt: null, createdAt: "2026-10-02T00:00:00.000Z" })
  assert.deepEqual(sortSchedules([later, sooner, never]).map((item) => item.id), ["b", "c", "a"])

  const sameNext = [
    schedule({ id: "z", nextRunAt: null, createdAt: "2026-10-01T00:00:00.000Z" }),
    schedule({ id: "x", nextRunAt: null, createdAt: "2026-10-05T00:00:00.000Z" }),
  ]
  assert.deepEqual(sortSchedules(sameNext).map((item) => item.id), ["x", "z"])

  const identical = [
    schedule({ id: "z", nextRunAt: null, createdAt: "2026-10-01T00:00:00.000Z" }),
    schedule({ id: "a", nextRunAt: null, createdAt: "2026-10-01T00:00:00.000Z" }),
  ]
  assert.deepEqual(sortSchedules(identical).map((item) => item.id), ["a", "z"])
})

test("sorting never mutates the caller's array", () => {
  const input = [schedule({ id: "b" }), schedule({ id: "a" })]
  const copy = [...input]
  sortSchedules(input)
  assert.deepEqual(input, copy)
})
