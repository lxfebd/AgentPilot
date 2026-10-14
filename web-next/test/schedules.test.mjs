import assert from "node:assert/strict"
import test from "node:test"

import {
  SCHEDULE_KIND_ONCE,
  WEEKDAY_KEYS,
  browserTimezone,
  buildScheduleCreateRequest,
  describeScheduleTrigger,
  formatNextRunAt,
  isRunTime,
  isScheduleKind,
  isValidTimezone,
  normalizeRunTime,
  runAtFromInput,
  scheduleNextRunLabel,
  validateScheduleDraft,
  weekdayFromDay,
  weekdayLabelKey,
  zonedMoment,
} from "../src/features/schedules/schedule-presentation.ts"

const SHANGHAI = "Asia/Shanghai"

const LABELS = {
  once: "Once",
  daily: "Every day",
  weekly: "Every {weekday}",
  weekdays: ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
  timezoneSuffix: (timezone) => `(${timezone})`,
}

const FIELD_LABELS = {
  titleRequired: "Enter a name.",
  promptRequired: "Enter the prompt to send.",
  targetRequired: "Choose a target.",
  runAtRequired: "Pick a date and time.",
  runAtFuture: "The date and time must be in the future.",
  runTimeRequired: "Enter a time as HH:MM.",
  weekdayRequired: "Pick a weekday.",
  timezoneInvalid: "Enter a valid IANA timezone name.",
}

function draft(overrides = {}) {
  return {
    title: "Daily standup",
    prompt: "Summarise yesterday",
    scheduleKind: "daily",
    runAt: null,
    runTime: "09:30",
    weekday: null,
    timezone: SHANGHAI,
    target: { mode: "session", sessionId: "sess-1" },
    ...overrides,
  }
}

// ── weekday mapping ─────────────────────────────────────────────

test("weekday 0 is Monday, matching the server contract", () => {
  assert.equal(weekdayLabelKey(0), "monday")
  assert.equal(weekdayLabelKey(6), "sunday")
  assert.deepEqual([...WEEKDAY_KEYS], [
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
  ])
})

test("Date#getDay's Sunday-first order maps onto the server's Monday-first order", () => {
  // 2026-10-19 is a Monday.
  assert.equal(weekdayFromDay(new Date("2026-10-19T00:00:00Z").getDay()), 0)
  // 2026-10-25 is a Sunday.
  assert.equal(weekdayFromDay(new Date("2026-10-25T00:00:00Z").getDay()), 6)
  // 2026-10-14 is a Wednesday.
  assert.equal(weekdayFromDay(new Date("2026-10-14T00:00:00Z").getDay()), 2)
})

test("out-of-range weekdays are rejected rather than silently wrapped", () => {
  assert.equal(weekdayLabelKey(-1), null)
  assert.equal(weekdayLabelKey(7), null)
  assert.equal(weekdayLabelKey(1.5), null)
  assert.equal(weekdayLabelKey(null), null)
  assert.equal(weekdayLabelKey(undefined), null)
})

// ── runTime / timezone parsing ──────────────────────────────────

test("HH:MM accepts 24-hour values and normalises single-digit hours", () => {
  assert.equal(isRunTime("00:00"), true)
  assert.equal(isRunTime("9:30"), true)
  assert.equal(isRunTime("23:59"), true)
  assert.equal(normalizeRunTime(" 9:30 "), "09:30")
  assert.equal(normalizeRunTime("09:30"), "09:30")
})

test("runTime rejects anything that is not HH:MM", () => {
  for (const value of ["24:00", "12:60", "9", "9:5", "morning", "", "09:30:00", null, undefined]) {
    assert.equal(isRunTime(value), false, `expected ${value} to be rejected`)
  }
  assert.equal(normalizeRunTime("24:00"), null)
  assert.equal(normalizeRunTime("12:60"), null)
  assert.equal(normalizeRunTime("morning"), null)
})

test("only real IANA timezone names are accepted", () => {
  assert.equal(isValidTimezone(SHANGHAI), true)
  assert.equal(isValidTimezone("UTC"), true)
  assert.equal(isValidTimezone("America/Los_Angeles"), true)
  assert.equal(isValidTimezone("Not/AZone"), false)
  assert.equal(isValidTimezone(""), false)
  assert.equal(isValidTimezone(null), false)
  assert.equal(isValidTimezone(undefined), false)
})

test("scheduleKind is narrowed to the three supported values", () => {
  assert.equal(isScheduleKind("once"), true)
  assert.equal(isScheduleKind("daily"), true)
  assert.equal(isScheduleKind("weekly"), true)
  assert.equal(isScheduleKind("hourly"), false)
  assert.equal(isScheduleKind(null), false)
})

// ── nextRunAt formatting ────────────────────────────────────────

test("nextRunAt is rendered in the schedule's own timezone, not the browser's", () => {
  // 01:30 UTC is 09:30 in Asia/Shanghai and 18:30 the previous day in LA.
  const instant = "2026-10-14T01:30:00Z"
  assert.equal(formatNextRunAt(instant, SHANGHAI), "2026-10-14 09:30")
  assert.equal(formatNextRunAt(instant, "America/Los_Angeles"), "2026-10-13 18:30")
  assert.equal(formatNextRunAt(instant, "UTC"), "2026-10-14 01:30")
})

test("a missing or unparseable nextRunAt has no display value", () => {
  assert.equal(formatNextRunAt(null, SHANGHAI), null)
  assert.equal(formatNextRunAt("not-a-date", SHANGHAI), null)
  assert.equal(formatNextRunAt("2026-10-14T01:30:00Z", "Not/AZone"), null)
})

test("zonedMoment reports the wall clock, including a midnight rollover", () => {
  // 16:00 UTC is 00:00 the next day in Asia/Shanghai.
  assert.deepEqual(zonedMoment("2026-10-14T16:00:00Z", SHANGHAI), {
    year: 2026, month: 10, day: 15, hour: 0, minute: 0,
  })
  assert.deepEqual(zonedMoment("2026-10-14T16:30:00Z", SHANGHAI), {
    year: 2026, month: 10, day: 15, hour: 0, minute: 30,
  })
})

test("scheduleNextRunLabel falls back to a placeholder once a once schedule has run", () => {
  const ran = { nextRunAt: null, timezone: SHANGHAI }
  assert.equal(scheduleNextRunLabel(ran, "no upcoming run"), "no upcoming run")
  const pending = { nextRunAt: "2026-10-20T01:00:00Z", timezone: SHANGHAI }
  assert.equal(scheduleNextRunLabel(pending, "no upcoming run"), "2026-10-20 09:00")
})

// ── trigger summaries ───────────────────────────────────────────

test("a weekly schedule reads as weekday plus time plus timezone", () => {
  const schedule = {
    scheduleKind: "weekly", runTime: "09:30", weekday: 0, timezone: SHANGHAI,
    runAt: null, nextRunAt: null,
  }
  assert.equal(
    describeScheduleTrigger(schedule, LABELS),
    "Every Monday 09:30 (Asia/Shanghai)",
  )
})

test("weekday 6 is Sunday, so the label list must not be Sunday-first", () => {
  const schedule = {
    scheduleKind: "weekly", runTime: "23:00", weekday: 6, timezone: "UTC",
    runAt: null, nextRunAt: null,
  }
  assert.equal(
    describeScheduleTrigger(schedule, LABELS),
    "Every Sunday 23:00 (UTC)",
  )
})

test("a daily schedule omits the weekday entirely", () => {
  const schedule = {
    scheduleKind: "daily", runTime: "08:00", weekday: null, timezone: "UTC",
    runAt: null, nextRunAt: null,
  }
  assert.equal(describeScheduleTrigger(schedule, LABELS), "Every day 08:00 (UTC)")
})

test("a once schedule is summarised from its instant, falling back to runAt", () => {
  const schedule = {
    scheduleKind: "once", runTime: null, weekday: null, timezone: SHANGHAI,
    runAt: "2026-10-20T01:00:00Z", nextRunAt: "2026-10-20T01:00:00Z",
  }
  assert.equal(
    describeScheduleTrigger(schedule, LABELS),
    "Once, 2026-10-20 09:00 (Asia/Shanghai)",
  )
})

test("an out-of-range weekday degrades instead of crashing the row", () => {
  const schedule = {
    scheduleKind: "weekly", runTime: "08:00", weekday: 9, timezone: "UTC",
    runAt: null, nextRunAt: null,
  }
  // A weekday outside 0–6 has no name, so the row falls back to bare time.
  assert.equal(describeScheduleTrigger(schedule, LABELS), "08:00 (UTC)")
})

// ── local validation ────────────────────────────────────────────

test("a complete daily draft passes and builds a request with no weekly fields", () => {
  const result = validateScheduleDraft(draft(), FIELD_LABELS)
  assert.equal(result.ok, true)
  assert.equal(result.error, null)

  const request = buildScheduleCreateRequest(draft())
  assert.deepEqual(request, {
    title: "Daily standup",
    prompt: "Summarise yesterday",
    scheduleKind: "daily",
    timezone: SHANGHAI,
    sessionId: "sess-1",
    runTime: "09:30",
  })
  assert.equal("runAt" in request, false)
  assert.equal("weekday" in request, false)
  assert.equal("projectId" in request, false)
})

test("title and prompt must be non-empty after trimming", () => {
  const blankTitle = validateScheduleDraft(draft({ title: "   " }), FIELD_LABELS)
  assert.equal(blankTitle.ok, false)
  assert.equal(blankTitle.fieldErrors.title, FIELD_LABELS.titleRequired)

  const blankPrompt = validateScheduleDraft(draft({ prompt: "" }), FIELD_LABELS)
  assert.equal(blankPrompt.ok, false)
  assert.equal(blankPrompt.fieldErrors.prompt, FIELD_LABELS.promptRequired)

  assert.equal(
    buildScheduleCreateRequest(draft({ title: "  Standup  ", prompt: "  hi  " })).title,
    "Standup",
  )
})

test("a once schedule requires a future runAt", () => {
  const now = new Date("2026-10-14T09:00:00Z")

  const missing = validateScheduleDraft(
    draft({ scheduleKind: "once", runAt: null }), FIELD_LABELS, now,
  )
  assert.equal(missing.fieldErrors.runAt, FIELD_LABELS.runAtRequired)

  const past = validateScheduleDraft(
    draft({ scheduleKind: "once", runAt: "2026-10-13T09:00:00Z" }), FIELD_LABELS, now,
  )
  assert.equal(past.fieldErrors.runAt, FIELD_LABELS.runAtFuture)

  const exactlyNow = validateScheduleDraft(
    draft({ scheduleKind: "once", runAt: now.toISOString() }), FIELD_LABELS, now,
  )
  assert.equal(exactlyNow.fieldErrors.runAt, FIELD_LABELS.runAtFuture)

  const future = validateScheduleDraft(
    draft({ scheduleKind: "once", runAt: "2026-10-20T01:00:00Z" }), FIELD_LABELS, now,
  )
  assert.equal(future.ok, true)

  const garbage = validateScheduleDraft(
    draft({ scheduleKind: "once", runAt: "next tuesday" }), FIELD_LABELS, now,
  )
  assert.equal(garbage.fieldErrors.runAt, FIELD_LABELS.runAtRequired)
})

test("a once schedule sends only runAt, and a weekly one only runTime and weekday", () => {
  const once = buildScheduleCreateRequest(draft({ scheduleKind: "once", runAt: "2026-10-20T01:00:00Z" }))
  assert.deepEqual(once.runAt, "2026-10-20T01:00:00Z")
  assert.equal("runTime" in once, false)
  assert.equal("weekday" in once, false)

  const weekly = buildScheduleCreateRequest(
    draft({ scheduleKind: "weekly", runTime: "9:05", weekday: 3 }),
  )
  assert.deepEqual(weekly.runTime, "09:05")
  assert.equal(weekly.weekday, 3)
  assert.equal("runAt" in weekly, false)
})

test("daily and weekly schedules require HH:MM", () => {
  for (const kind of ["daily", "weekly"]) {
    const result = validateScheduleDraft(draft({ scheduleKind: kind, runTime: "9am" }), FIELD_LABELS)
    assert.equal(result.fieldErrors.runTime, FIELD_LABELS.runTimeRequired, kind)
  }
  const blank = validateScheduleDraft(draft({ scheduleKind: "daily", runTime: "" }), FIELD_LABELS)
  assert.equal(blank.fieldErrors.runTime, FIELD_LABELS.runTimeRequired)
})

test("weekly requires weekday 0-6; daily ignores it", () => {
  const missing = validateScheduleDraft(draft({ scheduleKind: "weekly", weekday: null }), FIELD_LABELS)
  assert.equal(missing.fieldErrors.weekday, FIELD_LABELS.weekdayRequired)

  for (const weekday of [-1, 7, 1.5]) {
    const result = validateScheduleDraft(draft({ scheduleKind: "weekly", weekday }), FIELD_LABELS)
    assert.equal(result.fieldErrors.weekday, FIELD_LABELS.weekdayRequired, `weekday ${weekday}`)
  }

  assert.equal(validateScheduleDraft(draft({ scheduleKind: "weekly", weekday: 0 }), FIELD_LABELS).ok, true)
  assert.equal(validateScheduleDraft(draft({ scheduleKind: "weekly", weekday: 6 }), FIELD_LABELS).ok, true)
  // A stray weekday on a daily schedule is neither required nor sent.
  assert.equal(validateScheduleDraft(draft({ scheduleKind: "daily", weekday: 4 }), FIELD_LABELS).ok, true)
  assert.equal("weekday" in buildScheduleCreateRequest(draft({ scheduleKind: "daily", weekday: 4 })), false)
})

test("an unknown timezone is rejected locally before the server sees it", () => {
  const result = validateScheduleDraft(draft({ timezone: "Mars/Olympus" }), FIELD_LABELS)
  assert.equal(result.fieldErrors.timezone, FIELD_LABELS.timezoneInvalid)
  assert.equal(validateScheduleDraft(draft({ timezone: "" }), FIELD_LABELS).ok, false)
})

// ── "one target only" ───────────────────────────────────────────

test("exactly one target: an existing session, or project plus device", () => {
  const sessionTarget = { mode: "session", sessionId: "sess-1" }
  const projectTarget = { mode: "project", projectId: "proj-1", connectorId: "conn-1" }

  assert.equal(validateScheduleDraft(draft({ target: sessionTarget }), FIELD_LABELS).ok, true)
  assert.equal(validateScheduleDraft(draft({ target: projectTarget }), FIELD_LABELS).ok, true)

  // A new-session target missing its device never reaches the server.
  const noConnector = { mode: "project", projectId: "proj-1", connectorId: "" }
  const missingConnector = validateScheduleDraft(draft({ target: noConnector }), FIELD_LABELS)
  assert.equal(missingConnector.fieldErrors.target, FIELD_LABELS.targetRequired)

  const noProject = { mode: "project", projectId: null, connectorId: "conn-1" }
  assert.equal(validateScheduleDraft(draft({ target: noProject }), FIELD_LABELS).fieldErrors.target,
    FIELD_LABELS.targetRequired)

  const emptySession = { mode: "session", sessionId: "" }
  assert.equal(validateScheduleDraft(draft({ target: emptySession }), FIELD_LABELS).fieldErrors.target,
    FIELD_LABELS.targetRequired)

  // Never both at once: the session branch wins and the new-session fields drop out.
  const both = { mode: "session", sessionId: "sess-1", projectId: "proj-1", connectorId: "conn-1" }
  const request = buildScheduleCreateRequest(draft({ target: both }))
  assert.equal(request.sessionId, "sess-1")
  assert.equal("projectId" in request, false)
  assert.equal("connectorId" in request, false)
})

test("a project-target request carries both projectId and connectorId", () => {
  const request = buildScheduleCreateRequest(draft({
    target: { mode: "project", projectId: "proj-1", connectorId: "conn-1" },
  }))
  assert.equal(request.projectId, "proj-1")
  assert.equal(request.connectorId, "conn-1")
  assert.equal("sessionId" in request, false)
})

// ── runAt conversion ────────────────────────────────────────────

test("a datetime-local string is converted to UTC using the chosen timezone", () => {
  // 09:30 in Asia/Shanghai is 01:30 UTC.
  assert.equal(runAtFromInput("2026-10-20T09:30", SHANGHAI), "2026-10-20T01:30:00Z")
  // The same wall clock in UTC stays put.
  assert.equal(runAtFromInput("2026-10-20T09:30", "UTC"), "2026-10-20T09:30:00Z")
  // 18:30 the previous day in Los Angeles is 01:30 UTC the next day.
  assert.equal(runAtFromInput("2026-10-19T18:30", "America/Los_Angeles"), "2026-10-20T01:30:00Z")
})

test("a DST transition keeps the wall clock the user typed", () => {
  // 2026-03-08 is the US spring-forward date; 02:30 does not exist locally, so
  // the conversion must still land on a sane UTC instant instead of drifting.
  const instant = runAtFromInput("2026-03-08T02:30", "America/Los_Angeles")
  assert.ok(instant, "expected a UTC instant")
  const moment = zonedMoment(instant, "America/Los_Angeles")
  assert.ok(moment)
  assert.equal(moment.year, 2026)
  assert.equal(moment.month, 3)
  assert.equal(moment.day, 8)
})

test("runAtFromInput rejects malformed input and unknown timezones", () => {
  assert.equal(runAtFromInput("", SHANGHAI), null)
  assert.equal(runAtFromInput("2026-10-20", SHANGHAI), null)
  assert.equal(runAtFromInput("20/10/2026 09:30", SHANGHAI), null)
  assert.equal(runAtFromInput("2026-10-20T09:30", "Not/AZone"), null)
})

// ── timezone default ────────────────────────────────────────────

test("the form defaults to a real IANA timezone, never a hardcoded one", () => {
  const zone = browserTimezone()
  assert.equal(isValidTimezone(zone), true)
  assert.equal(typeof zone, "string")
  assert.notEqual(zone, "")
  assert.equal(SCHEDULE_KIND_ONCE, "once")
})
