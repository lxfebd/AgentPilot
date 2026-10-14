import type {
  ScheduleCreateRequest,
  ScheduleKind,
  SchedulePatchRequest,
  ScheduleView,
} from "@/features/dashboard/types"

/**
 * Presentation rules for the schedules page.
 *
 * The server owns the schedule; the client only explains it. Every helper here
 * is pure so the wording and the validation stay testable without a DOM, and
 * no timezone is ever hardcoded.
 */

export type ScheduleTargetMode = "session" | "project"

export type ScheduleTarget = {
  sessionId: string | null
  projectId: string | null
  connectorId: string | null
}

export type ScheduleFormDraft = {
  title: string
  prompt: string
  scheduleKind: ScheduleKind
  runAt: string | null
  runTime: string | null
  weekday: number | null
  timezone: string
  /**
   * Which target half the form is showing. Kept as explicit state rather than
   * derived from the id fields: a user who clears the project they picked would
   * otherwise see the form silently flip back to the session tab.
   */
  targetMode: ScheduleTargetMode
} & ScheduleTarget

export type ScheduleFormErrors = {
  title?: string
  prompt?: string
  runAt?: string
  runTime?: string
  weekday?: string
  target?: string
}

/** The browser's IANA zone. Never `UTC` or `Asia/Shanghai` unless it really is. */
export function browserTimezone(): string {
  try {
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
    return zone && zone.trim() ? zone : "UTC"
  } catch {
    return "UTC"
  }
}

const HH_MM = /^([01]\d|2[0-3]):([0-5]\d)$/

/** `HH:MM` in 24-hour form, the only `runTime` shape the server accepts. */
export function isValidRunTime(value: string | null | undefined): value is string {
  return typeof value === "string" && HH_MM.test(value.trim())
}

/** 0 = Monday … 6 = Sunday, matching the server contract. */
export function isValidWeekday(value: number | null | undefined): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 && value <= 6
}

/** The weekday names in server order (0 = Monday), used for the summary. */
export const WEEKDAY_LABELS = [
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
] as const

/** Server weekday index (0 = Monday) to its name. */
export function weekdayName(weekday: number | null | undefined): string | null {
  if (!isValidWeekday(weekday)) return null
  return WEEKDAY_LABELS[weekday] ?? null
}

/**
 * Offset in milliseconds to add to a UTC instant to read the wall clock of
 * `timezone`. `null` when the zone is unknown.
 */
function zoneOffsetMs(instant: Date, timezone: string): number | null {
  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone: timezone,
      hour12: false,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    }).formatToParts(instant)
    const read = (type: string) => Number(parts.find((part) => part.type === type)?.value)
    const asUtc = Date.UTC(
      read("year"),
      read("month") - 1,
      read("day"),
      read("hour") % 24,
      read("minute"),
      read("second"),
    )
    if (Number.isNaN(asUtc)) return null
    return asUtc - instant.getTime()
  } catch {
    return null
  }
}

function zoneParts(instant: Date, timezone: string) {
  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone: timezone,
      hour12: false,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    }).formatToParts(instant)
    const read = (type: string) => parts.find((part) => part.type === type)?.value ?? ""
    return {
      year: read("year"),
      month: read("month"),
      day: read("day"),
      hour: read("hour"),
      minute: read("minute"),
    }
  } catch {
    return null
  }
}

function parseInstant(value: string | null | undefined): Date | null {
  if (typeof value !== "string" || !value.trim()) return null
  const instant = new Date(value)
  return Number.isNaN(instant.getTime()) ? null : instant
}

/** `2026-10-20 09:00` — the local wall clock of a UTC instant in `timezone`. */
export function formatInstantLocal(value: string | null | undefined, timezone: string): string | null {
  const instant = parseInstant(value)
  if (!instant) return null
  const parts = zoneParts(instant, timezone)
  if (!parts) return instant.toISOString()
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`
}

/**
 * Local wall-clock input value (`YYYY-MM-DDTHH:mm`) for a UTC instant, ready
 * for `<input type="datetime-local">`.
 */
export function toDateTimeLocalValue(value: string | null | undefined, timezone: string): string {
  const formatted = formatInstantLocal(value, timezone)
  return formatted ? formatted.replace(" ", "T") : ""
}

/** A local wall-clock string interpreted in `timezone`, as a UTC instant. */
export function parseLocalDateTime(
  value: string | null | undefined,
  timezone: string,
): Date | null {
  if (typeof value !== "string" || !value.trim()) return null
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/.exec(value.trim())
  if (!match) return null
  const [, year, month, day, hour, minute] = match
  const monthIndex = Number(month) - 1
  if (monthIndex < 0 || monthIndex > 11) return null
  const dayOfMonth = Number(day)
  if (dayOfMonth < 1 || dayOfMonth > daysInMonth(Number(year), monthIndex)) return null
  if (Number(hour) > 23 || Number(minute) > 59) return null
  const asUtc = Date.UTC(Number(year), monthIndex, dayOfMonth, Number(hour), Number(minute))
  const instant = new Date(asUtc)
  if (Number.isNaN(instant.getTime())) return null
  const offset = zoneOffsetMs(instant, timezone)
  if (offset === null) return null
  const shifted = new Date(asUtc - offset)
  // A wall clock inside a DST gap never exists; reject it instead of guessing.
  const resolved = zoneOffsetMs(shifted, timezone)
  if (resolved === null || resolved !== offset) return null
  return shifted
}

function daysInMonth(year: number, monthIndex: number): number {
  return new Date(Date.UTC(year, monthIndex + 1, 0)).getUTCDate()
}

/** A UTC instant is only "future" when it is strictly ahead of `now`. */
export function isFutureInstant(value: string | null | undefined, now: Date): boolean {
  const instant = parseInstant(value)
  if (!instant) return false
  return instant.getTime() > now.getTime()
}

/**
 * Trigger summary: "Every Monday 09:30 (Asia/Shanghai)" for a weekly
 * schedule, "Once, 2026-10-20 09:00 (Asia/Shanghai)" for a one-shot one.
 *
 * Labels are injected by the caller so this module stays free of UI copy, and
 * so the wording matches the Web and iOS clients exactly.
 */
export type TriggerSummaryLabels = {
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

export function formatTriggerSummary(schedule: ScheduleView, labels: TriggerSummaryLabels): string {
  const timezone = schedule.timezone?.trim() || "UTC"
  const suffix = labels.timezoneSuffix(timezone)
  if (schedule.scheduleKind === "once") {
    const when = formatInstantLocal(schedule.runAt ?? schedule.nextRunAt, timezone)
    // Same shape as the Web and iOS clients: only the leading label is
    // comma-separated; the timezone stays attached to the timestamp.
    const pieces = [labels.once]
    if (when) pieces.push(when)
    return suffix ? `${pieces.join(", ")} ${suffix}` : pieces.join(", ")
  }
  const runTime = isValidRunTime(schedule.runTime) ? schedule.runTime.trim() : null
  if (schedule.scheduleKind === "weekly") {
    const weekday = weekdayName(schedule.weekday)
    if (!weekday || !runTime) return "—"
    return [labels.weekly.replace("{weekday}", weekday), runTime, suffix].filter(Boolean).join(" ")
  }
  if (!runTime) return "—"
  return [labels.daily, runTime, suffix].filter(Boolean).join(" ")
}

/**
 * "Next run" text: the next trigger in the schedule's own timezone, or `null`
 * when there is nothing to show.
 */
export function formatNextRun(
  schedule: Pick<ScheduleView, "nextRunAt">,
  timezone: string,
): string | null {
  return formatInstantLocal(schedule.nextRunAt, timezone)
}

/** True once a `once` schedule has run and therefore never fires again. */
export function scheduleHasFinished(
  schedule: Pick<ScheduleView, "scheduleKind" | "nextRunAt">,
): boolean {
  return schedule.scheduleKind === "once" && !schedule.nextRunAt
}

/** Exactly one target: an existing `sessionId`, or `projectId` + `connectorId`. */
export function scheduleTargetError(target: ScheduleTarget): "targetRequired" | null {
  const sessionId = Boolean(target.sessionId?.trim())
  const projectId = Boolean(target.projectId?.trim())
  const connectorId = Boolean(target.connectorId?.trim())

  if (sessionId && (projectId || connectorId)) return "targetRequired"
  if (!sessionId && !projectId && !connectorId) return "targetRequired"
  if (!sessionId && projectId !== connectorId) return "targetRequired"
  return null
}

/**
 * Validate the local create form. `now` is injected so the "must be future"
 * rule is deterministic in tests.
 */
export function validateScheduleForm(draft: ScheduleFormDraft, now: Date): ScheduleFormErrors {
  const errors: ScheduleFormErrors = {}

  if (!draft.title.trim()) errors.title = "titleRequired"
  if (!draft.prompt.trim()) errors.prompt = "promptRequired"

  if (draft.scheduleKind === "once") {
    const runAt = parseLocalDateTime(draft.runAt, draft.timezone)
    if (!runAt) {
      errors.runAt = "runAtRequired"
    } else if (runAt.getTime() <= now.getTime()) {
      errors.runAt = "runAtMustBeFuture"
    }
  } else if (!isValidRunTime(draft.runTime)) {
    errors.runTime = "runTimeRequired"
  }

  if (draft.scheduleKind === "weekly" && !isValidWeekday(draft.weekday)) {
    errors.weekday = "weekdayRequired"
  }

  const target = scheduleTargetError(draft)
  if (target) errors.target = target

  return errors
}

/** The target half of a create/patch body, keeping exactly one target. */
export function buildScheduleTarget(target: ScheduleTarget): {
  sessionId?: string | null
  projectId?: string | null
  connectorId?: string | null
} {
  const sessionId = target.sessionId?.trim() ?? ""
  const projectId = target.projectId?.trim() ?? ""
  const connectorId = target.connectorId?.trim() ?? ""

  if (sessionId) return { sessionId }
  return { projectId: projectId || null, connectorId: connectorId || null }
}

/** Build the create body, dropping the fields the chosen kind does not use. */
export function buildScheduleCreateRequest(draft: ScheduleFormDraft): ScheduleCreateRequest {
  const body: ScheduleCreateRequest = {
    title: draft.title.trim(),
    prompt: draft.prompt.trim(),
    scheduleKind: draft.scheduleKind,
    timezone: draft.timezone,
    ...buildScheduleTarget(draft),
  }

  if (draft.scheduleKind === "once") {
    const runAt = parseLocalDateTime(draft.runAt, draft.timezone)
    if (runAt) body.runAt = runAt.toISOString()
    return body
  }

  if (isValidRunTime(draft.runTime)) body.runTime = draft.runTime.trim()
  if (draft.scheduleKind === "weekly" && isValidWeekday(draft.weekday)) {
    body.weekday = draft.weekday
  }
  return body
}

/** The body the enable/disable toggle sends. */
export function buildScheduleEnabledPatch(enabled: boolean): SchedulePatchRequest {
  return { enabled }
}

/** Empty create-form state, defaulted to the browser timezone. */
export function emptyScheduleDraft(timezone: string = browserTimezone()): ScheduleFormDraft {
  return {
    title: "",
    prompt: "",
    scheduleKind: "daily",
    runAt: null,
    runTime: "09:00",
    weekday: 0,
    timezone,
    targetMode: "session",
    sessionId: null,
    projectId: null,
    connectorId: null,
  }
}

/** Sort the list newest first by trigger, so the page never depends on API order. */
export function sortSchedules(schedules: ScheduleView[]): ScheduleView[] {
  return [...schedules].sort(
    (left, right) =>
      (right.nextRunAt ?? "").localeCompare(left.nextRunAt ?? "") ||
      right.createdAt.localeCompare(left.createdAt) ||
      left.id.localeCompare(right.id),
  )
}
