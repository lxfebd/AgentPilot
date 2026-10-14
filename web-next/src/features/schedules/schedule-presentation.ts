import type {
  ScheduleKind,
  ScheduleView,
} from "@/features/dashboard/types"

/**
 * Presentation and local-validation rules for scheduled agent tasks.
 *
 * Everything here is pure so it can be unit tested without React. The server
 * stays the authority on persistence; these helpers only keep obviously wrong
 * input from reaching it (see `docs/api/schedules.md`).
 *
 * Time contract from the server:
 *   - `weekday` is 0 = Monday … 6 = Sunday.
 *   - `runTime` is `HH:MM` interpreted in `timezone`.
 *   - `runAt` is a UTC ISO-8601 instant and must be in the future for `once`.
 *   - A schedule targets exactly one of an existing session or a new session
 *     (`projectId` + `connectorId`).
 */

export const SCHEDULE_KIND_ONCE = "once"
export const SCHEDULE_KIND_DAILY = "daily"
export const SCHEDULE_KIND_WEEKLY = "weekly"

export const WEEKDAY_COUNT = 7

/** Server weekday order: Monday first. `Date#getDay` is Sunday first. */
export const WEEKDAY_KEYS = [
  "monday",
  "tuesday",
  "wednesday",
  "thursday",
  "friday",
  "saturday",
  "sunday",
] as const

export type WeekdayKey = (typeof WEEKDAY_KEYS)[number]

export function isScheduleKind(value: unknown): value is ScheduleKind {
  return value === SCHEDULE_KIND_ONCE
    || value === SCHEDULE_KIND_DAILY
    || value === SCHEDULE_KIND_WEEKLY
}

export function isWeekdayKey(value: unknown): value is WeekdayKey {
  return typeof value === "string" && (WEEKDAY_KEYS as readonly string[]).includes(value)
}

/** `Date#getDay()` (0 = Sunday) → server `weekday` (0 = Monday). */
export function weekdayFromDay(day: number): number {
  return (day + 6) % WEEKDAY_COUNT
}

export function weekdayLabelKey(weekday: number | null | undefined): WeekdayKey | null {
  if (typeof weekday !== "number" || !Number.isInteger(weekday)) return null
  if (weekday < 0 || weekday >= WEEKDAY_COUNT) return null
  return WEEKDAY_KEYS[weekday] ?? null
}

export type ScheduleTriggerSummary = {
  /** `once` | `daily` | `weekly` — the raw `scheduleKind`. */
  kind: ScheduleKind
  /** `HH:MM`, or null for `once`. */
  runTime: string | null
  /** Server weekday 0–6, or null outside `weekly`. */
  weekday: number | null
  /** IANA timezone name, always non-empty for a valid schedule. */
  timezone: string
}

/** The local-time wall-clock fields of a UTC instant, as the schedule sees them. */
export type ZonedMoment = {
  year: number
  month: number
  day: number
  hour: number
  minute: number
}

const HH_MM_PATTERN = /^(\d{1,2}):([0-5]\d)$/

/**
 * A timezone is only usable when `Intl` can actually format with it; an unknown
 * IANA name is rejected by the server with a 400.
 */
export function isValidTimezone(timezone: string | null | undefined): boolean {
  if (typeof timezone !== "string" || !timezone.trim()) return false
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: timezone })
    return true
  } catch {
    return false
  }
}

/** `HH:MM` in 24-hour form. Leading zeros and single-digit hours both count. */
export function isRunTime(value: string | null | undefined): boolean {
  if (typeof value !== "string") return false
  const match = HH_MM_PATTERN.exec(value.trim())
  if (!match) return false
  return Number(match[1]) <= 23 && Number(match[2]) <= 59
}

/** Normalise `9:30` / ` 09:30 ` to `09:30`; returns null when unparseable. */
export function normalizeRunTime(value: string | null | undefined): string | null {
  if (typeof value !== "string") return null
  const trimmed = value.trim()
  if (!trimmed) return null
  const match = /^(\d{1,2}):(\d{2})$/.exec(trimmed)
  if (!match) return null
  const hour = Number(match[1])
  const minute = Number(match[2])
  if (!Number.isInteger(hour) || !Number.isInteger(minute)) return null
  if (hour > 23 || minute > 59) return null
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`
}

export function parseUtcInstant(value: string | null | undefined): Date | null {
  if (typeof value !== "string" || !value.trim()) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

/** Local wall-clock fields of a UTC instant in `timezone`. */
export function zonedMoment(instant: string | Date | null | undefined, timezone: string): ZonedMoment | null {
  const date = instant instanceof Date ? instant : parseUtcInstant(instant)
  if (!date || !isValidTimezone(timezone)) return null
  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone: timezone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    }).formatToParts(date)
    const lookup: Record<string, string> = {}
    for (const part of parts) {
      if (part.type !== "literal") lookup[part.type] = part.value
    }
    return {
      year: Number(lookup.year),
      month: Number(lookup.month),
      day: Number(lookup.day),
      hour: Number(lookup.hour),
      minute: Number(lookup.minute),
    }
  } catch {
    return null
  }
}

export function formatDateInput(value: ZonedMoment): string {
  return `${value.year}-${pad(value.month)}-${pad(value.day)}`
}

export function formatTimeInput(value: ZonedMoment): string {
  return `${pad(value.hour)}:${pad(value.minute)}`
}

function pad(value: number): string {
  return String(value).padStart(2, "0")
}

/**
 * Format a UTC instant for display as `2026-10-20 09:00` in `timezone`. Returns
 * null when the instant is missing, unparseable, or the timezone is unknown.
 * Every timestamp a row shows goes through here, so a failure time reads the
 * same way as the next run it failed to produce.
 */
export function formatZonedInstant(
  instant: string | null | undefined,
  timezone: string,
): string | null {
  const moment = zonedMoment(instant, timezone)
  if (!moment) return null
  return `${formatDateInput(moment)} ${formatTimeInput(moment)}`
}

/**
 * Format `nextRunAt` for display: `2026-10-20 09:00`. Returns null when the
 * instant is missing, unparseable, or the timezone is unknown.
 */
export function formatNextRunAt(
  nextRunAt: string | null | undefined,
  timezone: string,
): string | null {
  return formatZonedInstant(nextRunAt, timezone)
}

/**
 * Human trigger summary, e.g. `Every Monday 09:30 (Asia/Shanghai)` or
 * `Once, 2026-10-20 09:00`. The caller supplies the translated fragments so
 * this module stays free of UI copy.
 */
export type ScheduleSummaryLabels = {
  /** `once` — e.g. "Once" */
  once: string
  /** `daily` — e.g. "Every day" */
  daily: string
  /** `weekly` — e.g. "Every {weekday}" */
  weekly: string
  /** Weekday names, Monday first. */
  weekdays: readonly string[]
  /** Wraps the timezone, e.g. "({timezone})" */
  timezoneSuffix: (timezone: string) => string
}

export function describeScheduleTrigger(
  schedule: Pick<ScheduleView, "scheduleKind" | "runTime" | "weekday" | "timezone" | "runAt" | "nextRunAt">,
  labels: ScheduleSummaryLabels,
): string {
  const timezone = schedule.timezone || "UTC"
  const suffix = labels.timezoneSuffix(timezone)
  if (schedule.scheduleKind === SCHEDULE_KIND_ONCE) {
    const moment = zonedMoment(schedule.runAt ?? schedule.nextRunAt, timezone)
    const stamp = moment
      ? `${formatDateInput(moment)} ${formatTimeInput(moment)}`
      : schedule.runAt ?? ""
    // Same shape as the Desktop and iOS clients:
    // "Once, 2026-10-20 09:30 (Asia/Shanghai)". Only the leading label is
    // comma-separated; the timezone stays attached to the timestamp. Built
    // explicitly so a timezone name can never be mistaken for a separator.
    const pieces = [labels.once]
    if (stamp) pieces.push(stamp)
    return suffix ? `${pieces.join(", ")} ${suffix}` : pieces.join(", ")
  }

  const runTime = isRunTime(schedule.runTime) ? (schedule.runTime ?? "").trim() : null
  if (schedule.scheduleKind === SCHEDULE_KIND_WEEKLY) {
    const weekday = weekdayLabelKey(schedule.weekday)
    const weekdayLabel = weekday ? labels.weekdays[WEEKDAY_KEYS.indexOf(weekday)] : ""
    // A weekday outside 0–6 has no name; drop the placeholder rather than
    // rendering the raw `{weekday}` template to the user.
    const head = weekdayLabel ? labels.weekly.replace("{weekday}", weekdayLabel) : ""
    return [head, runTime, suffix].filter(Boolean).join(" ")
  }

  return [labels.daily, runTime, suffix].filter(Boolean).join(" ")
}

/** `nextRunAt` shown for a row; a `once` schedule that already ran has none. */
export function scheduleNextRunLabel(
  schedule: Pick<ScheduleView, "nextRunAt" | "timezone">,
  emptyLabel: string,
): string {
  return formatNextRunAt(schedule.nextRunAt, schedule.timezone) ?? emptyLabel
}

/**
 * A failed scheduled run, ready to show on its row.
 *
 * The server clears `lastError` / `lastErrorAt` on a successful run and on every
 * edit, so whatever is shown is the current state and never a stale failure.
 * A schedule whose target (session, project or connector) no longer exists is
 * switched off by the server as well: `enabled` is false and `lastError` says
 * why, so that case reads as "turned off: …" instead of "last run failed: …".
 *
 * The caller supplies the translated templates, as with `describeScheduleTrigger`,
 * so this module stays free of UI copy.
 */
export type ScheduleFailureLabels = {
  /** Failure with a readable time — e.g. "Last run failed at {time}: {reason}" */
  failedAt: string
  /** Failure whose time is missing or unparseable — "Last run failed: {reason}" */
  failed: string
  /** Switched off by the server, time known — "Turned off at {time}: {reason}" */
  disabledAt: string
  /** Switched off by the server, time unknown — "Turned off: {reason}" */
  disabled: string
}

export type ScheduleFailure = {
  /** One line: the reason, plus when it happened whenever that can be shown. */
  text: string
  /** True when the schedule is off too, i.e. the failure explains why it stopped. */
  switchedOff: boolean
}

/**
 * `null` when there is nothing to say: no `lastError`, or one that is only
 * whitespace. A missing or unparseable `lastErrorAt`, and an unknown timezone,
 * both degrade to the reason alone rather than hiding the failure.
 */
export function describeScheduleFailure(
  schedule: Pick<ScheduleView, "lastError" | "lastErrorAt" | "enabled" | "timezone">,
  labels: ScheduleFailureLabels,
): ScheduleFailure | null {
  const reason = typeof schedule.lastError === "string" ? schedule.lastError.trim() : ""
  if (!reason) return null

  const time = formatZonedInstant(schedule.lastErrorAt, schedule.timezone)
  const switchedOff = schedule.enabled === false
  const template = switchedOff
    ? (time ? labels.disabledAt : labels.disabled)
    : (time ? labels.failedAt : labels.failed)

  return { text: fillFailureTemplate(template, time, reason), switchedOff }
}

/**
 * Fill `{time}` / `{reason}`. The replacements are functions so a reason handed
 * over by the server cannot be reinterpreted as a replacement pattern (`$&`).
 */
function fillFailureTemplate(
  template: string,
  time: string | null,
  reason: string,
): string {
  return template
    .replace(/\{time\}/g, () => time ?? "")
    .replace(/\{reason\}/g, () => reason)
}

// ── Create-form validation ──────────────────────────────────────

export type ScheduleTargetDraft = {
  mode: "session" | "project"
  sessionId?: string | null
  projectId?: string | null
  connectorId?: string | null
}

export type ScheduleDraft = {
  title?: string | null
  prompt?: string | null
  scheduleKind: ScheduleKind
  runAt?: string | null
  runTime?: string | null
  weekday?: number | null
  timezone?: string | null
  target: ScheduleTargetDraft
}

export type ScheduleValidationLabels = {
  titleRequired: string
  promptRequired: string
  targetRequired: string
  runAtRequired: string
  runAtFuture: string
  runTimeRequired: string
  weekdayRequired: string
  timezoneInvalid: string
}

export type ScheduleValidationResult = {
  ok: boolean
  /** First blocking problem, ready to show under the form. */
  error: string | null
  fieldErrors: {
    title: string | null
    prompt: string | null
    target: string | null
    runAt: string | null
    runTime: string | null
    weekday: string | null
    timezone: string | null
  }
}

function emptyFieldErrors(): ScheduleValidationResult["fieldErrors"] {
  return {
    title: null,
    prompt: null,
    target: null,
    runAt: null,
    runTime: null,
    weekday: null,
    timezone: null,
  }
}

/** Exactly one target: an existing session, or `projectId` + `connectorId`. */
export function targetIsComplete(target: ScheduleTargetDraft): boolean {
  if (target.mode === "session") return Boolean(target.sessionId?.trim())
  return Boolean(target.projectId?.trim()) && Boolean(target.connectorId?.trim())
}

/**
 * Local mirror of the server's create rules. `now` is injectable so the
 * "must be in the future" rule is testable without waiting for a clock.
 */
export function validateScheduleDraft(
  draft: ScheduleDraft,
  labels: ScheduleValidationLabels,
  now: Date = new Date(),
): ScheduleValidationResult {
  const fieldErrors = emptyFieldErrors()

  if (!draft.title?.trim()) fieldErrors.title = labels.titleRequired
  if (!draft.prompt?.trim()) fieldErrors.prompt = labels.promptRequired
  if (!targetIsComplete(draft.target)) fieldErrors.target = labels.targetRequired
  if (!isValidTimezone(draft.timezone)) fieldErrors.timezone = labels.timezoneInvalid

  if (draft.scheduleKind === SCHEDULE_KIND_ONCE) {
    const runAt = draft.runAt?.trim() ?? ""
    if (!runAt) {
      fieldErrors.runAt = labels.runAtRequired
    } else {
      const instant = parseUtcInstant(runAt)
      if (!instant) {
        fieldErrors.runAt = labels.runAtRequired
      } else if (instant.getTime() <= now.getTime()) {
        fieldErrors.runAt = labels.runAtFuture
      }
    }
  } else {
    if (!isRunTime(draft.runTime)) fieldErrors.runTime = labels.runTimeRequired
    if (draft.scheduleKind === SCHEDULE_KIND_WEEKLY) {
      const weekday = draft.weekday
      const validWeekday = typeof weekday === "number"
        && Number.isInteger(weekday)
        && weekday >= 0
        && weekday < WEEKDAY_COUNT
      if (!validWeekday) fieldErrors.weekday = labels.weekdayRequired
    }
  }

  const firstError = Object.values(fieldErrors).find((value) => value !== null) ?? null
  return { ok: firstError === null, error: firstError, fieldErrors }
}

/**
 * Build the `ScheduleCreateRequest` payload from a validated draft. Only the
 * fields the chosen kind needs are sent, so `weekly` never carries a `runAt`
 * and `once` never carries a `runTime`.
 */
export function buildScheduleCreateRequest(draft: ScheduleDraft): {
  title: string
  prompt: string
  scheduleKind: ScheduleKind
  sessionId?: string | null
  projectId?: string | null
  connectorId?: string | null
  runAt?: string | null
  runTime?: string | null
  weekday?: number | null
  timezone?: string
  enabled?: boolean
} {
  const timezone = draft.timezone?.trim() || "UTC"
  const request: ReturnType<typeof buildScheduleCreateRequest> = {
    title: (draft.title ?? "").trim(),
    prompt: (draft.prompt ?? "").trim(),
    scheduleKind: draft.scheduleKind,
    timezone,
  }

  if (draft.target.mode === "session") {
    request.sessionId = draft.target.sessionId?.trim() || null
  } else {
    request.projectId = draft.target.projectId?.trim() || null
    request.connectorId = draft.target.connectorId?.trim() || null
  }

  if (draft.scheduleKind === SCHEDULE_KIND_ONCE) {
    const instant = parseUtcInstant(draft.runAt)
    request.runAt = instant ? toUtcInstant(instant) : null
  } else {
    request.runTime = normalizeRunTime(draft.runTime)
    if (draft.scheduleKind === SCHEDULE_KIND_WEEKLY) {
      request.weekday = draft.weekday ?? null
    }
  }

  return request
}

/** Default the create form to the browser's IANA timezone, never a hardcoded one. */
export function browserTimezone(): string {
  try {
    const resolved = Intl.DateTimeFormat().resolvedOptions().timeZone
    return isValidTimezone(resolved) ? resolved : "UTC"
  } catch {
    return "UTC"
  }
}

/** Initial `runAt` draft value: now + one hour, rendered in the chosen timezone. */
export function defaultRunAtInput(timezone: string, now: Date = new Date()): string {
  const soon = new Date(now.getTime() + 60 * 60 * 1000)
  const moment = zonedMoment(soon, timezone)
  if (!moment) return ""
  return `${formatDateInput(moment)}T${formatTimeInput(moment)}`
}

/** UTC ISO-8601 instant, `Z`-suffixed and without redundant milliseconds. */
export function toUtcInstant(date: Date): string {
  return date.toISOString().replace(/\.\d{3}Z$/, "Z")
}

/**
 * Convert a `datetime-local` input (`YYYY-MM-DDTHH:mm`, wall clock in
 * `timezone`) into the UTC ISO-8601 instant the server expects.
 */
export function runAtFromInput(input: string, timezone: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(input.trim())
  if (!match) return null
  const [, year, month, day, hour, minute] = match
  const utcGuess = new Date(Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
  ))
  if (Number.isNaN(utcGuess.getTime())) return null

  // The naive reading is UTC; the true instant is the one whose wall clock in
  // `timezone` equals the typed value. Two passes converge for every fixed
  // offset, including DST transitions.
  let candidate = utcGuess
  for (let pass = 0; pass < 3; pass += 1) {
    const moment = zonedMoment(candidate, timezone)
    if (!moment) return null
    const wallClock = Date.UTC(
      moment.year,
      moment.month - 1,
      moment.day,
      moment.hour,
      moment.minute,
    )
    const target = Date.UTC(
      Number(year),
      Number(month) - 1,
      Number(day),
      Number(hour),
      Number(minute),
    )
    const delta = wallClock - target
    if (delta === 0) return toUtcInstant(candidate)
    candidate = new Date(candidate.getTime() - delta)
  }
  return toUtcInstant(candidate)
}
