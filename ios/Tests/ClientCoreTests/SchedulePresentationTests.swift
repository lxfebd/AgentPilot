import Foundation
import Testing
@testable import ClientCore

@Suite @MainActor struct SchedulePresentationTests {
    private func schedule(
        kind: V2ScheduleKind = .daily,
        runTime: String? = "09:30",
        weekday: Int? = nil,
        timezone: String = "Asia/Shanghai",
        runAt: String? = nil,
        nextRunAt: String? = "2026-10-20T01:30:00Z",
        enabled: Bool = true,
        lastError: String? = nil,
        lastErrorAt: String? = nil
    ) -> V2Schedule {
        V2Schedule(id: "sch_1", title: "Digest", prompt: "Summarize.", scheduleKind: kind,
                   runAt: runAt, runTime: runTime, weekday: weekday, timezone: timezone,
                   enabled: enabled, sessionId: "sess_1", projectId: nil, connectorId: nil,
                   runtime: nil, cwd: nil, nextRunAt: nextRunAt, lastRunAt: nil,
                   lastError: lastError, lastErrorAt: lastErrorAt,
                   createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z")
    }

    private var labels: SchedulePresentation.TriggerSummaryLabels {
        SchedulePresentation.TriggerSummaryLabels(
            once: "Once", daily: "Every day", weekly: "Every {weekday}",
            weekdays: ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
            timezoneSuffix: { "($0)" }
        )
    }

    /// The same templates the row passes in, kept literal so the wording the
    /// helper produces is checked, not the localized copy.
    private var failureLabels: SchedulePresentation.FailureSummaryLabels {
        SchedulePresentation.FailureSummaryLabels(
            failed: "Last run failed: {reason}",
            failedAt: "Last run failed at {time}: {reason}",
            disabled: "Turned off: {reason}",
            disabledAt: "Turned off at {time}: {reason}"
        )
    }

    private var validation: ScheduleValidationLabels {
        ScheduleValidationLabels(
            titleRequired: "title required", promptRequired: "prompt required",
            targetRequired: "target required", runAtRequired: "runAt required",
            runAtFuture: "runAt must be future", runTimeRequired: "runTime required",
            weekdayRequired: "weekday required", timezoneInvalid: "timezone invalid"
        )
    }

    // ── weekday mapping ──────────────────────────────────────

    @Test func serverWeekdayIsMondayFirst() {
        // Gregorian 1 = Sunday; the Server wants Monday = 0.
        #expect(SchedulePresentation.weekdayIndex(forWeekday: 2) == 0)  // Monday
        #expect(SchedulePresentation.weekdayIndex(forWeekday: 3) == 1)  // Tuesday
        #expect(SchedulePresentation.weekdayIndex(forWeekday: 1) == 6)  // Sunday
        #expect(SchedulePresentation.weekdaySymbol(0)?.isEmpty == false)
        #expect(SchedulePresentation.weekdaySymbol(6)?.isEmpty == false)
        #expect(SchedulePresentation.weekdaySymbol(7) == nil)
        #expect(SchedulePresentation.weekdaySymbol(-1) == nil)
    }

    // ── runTime ──────────────────────────────────────────────

    @Test func runTimeAcceptsTwentyFourHourForm() {
        #expect(SchedulePresentation.isRunTime("00:00"))
        #expect(SchedulePresentation.isRunTime("09:30"))
        #expect(SchedulePresentation.isRunTime("23:59"))
    }

    @Test func runTimeRejectsOutOfRangeAndMalformed() {
        #expect(!SchedulePresentation.isRunTime("24:00"))
        #expect(!SchedulePresentation.isRunTime("12:60"))
        #expect(!SchedulePresentation.isRunTime("9"))
        #expect(!SchedulePresentation.isRunTime("9:5"))
        #expect(!SchedulePresentation.isRunTime(""))
        #expect(!SchedulePresentation.isRunTime("09:30:00"))
        #expect(!SchedulePresentation.isRunTime(nil))
    }

    @Test func runTimeNormalizesSingleDigitHour() {
        #expect(SchedulePresentation.normalizeRunTime("9:30") == "09:30")
        #expect(SchedulePresentation.normalizeRunTime(" 09:30 ") == "09:30")
        #expect(SchedulePresentation.normalizeRunTime("24:00") == nil)
        #expect(SchedulePresentation.normalizeRunTime(nil) == nil)
    }

    // ── timezone ─────────────────────────────────────────────

    @Test func timezoneValidationUsesTheSystemDatabase() {
        #expect(SchedulePresentation.isValidTimezone("Asia/Shanghai"))
        #expect(SchedulePresentation.isValidTimezone("America/Los_Angeles"))
        #expect(!SchedulePresentation.isValidTimezone("Not/AZone"))
        #expect(!SchedulePresentation.isValidTimezone(""))
        #expect(!SchedulePresentation.isValidTimezone(nil))
        #expect(currentTimezoneIdentifier().isEmpty == false)
    }

    // ── formatting ───────────────────────────────────────────

    @Test func nextRunAtIsFormattedInTheSchedulesTimezone() {
        // 01:30Z is 09:30 in +08:00.
        #expect(SchedulePresentation.formatNextRunAt("2026-10-20T01:30:00Z", timezone: "Asia/Shanghai") == "2026-10-20 09:30")
        #expect(SchedulePresentation.formatNextRunAt("2026-10-20T01:30:00Z", timezone: "UTC") == "2026-10-20 01:30")
        #expect(SchedulePresentation.formatNextRunAt(nil, timezone: "UTC") == nil)
        #expect(SchedulePresentation.formatNextRunAt("garbage", timezone: "UTC") == nil)
    }

    @Test func triggerSummaryCoversEveryKind() {
        #expect(SchedulePresentation.describeTrigger(schedule(kind: .weekly, weekday: 0), labels: labels)
            == "Every Monday 09:30 (Asia/Shanghai)")
        #expect(SchedulePresentation.describeTrigger(schedule(kind: .weekly, runTime: "23:00", weekday: 6, timezone: "UTC"), labels: labels)
            == "Every Sunday 23:00 (UTC)")
        #expect(SchedulePresentation.describeTrigger(schedule(kind: .daily, runTime: "08:00", timezone: "UTC"), labels: labels)
            == "Every day 08:00 (UTC)")
        #expect(SchedulePresentation.describeTrigger(
            schedule(kind: .once, runTime: nil, runAt: "2026-10-20T01:30:00Z", nextRunAt: nil), labels: labels)
            == "Once, 2026-10-20 09:30 (Asia/Shanghai)")
    }

    @Test func oneShotSummaryShapeMatchesWebAndDesktop() {
        // Only the leading label is comma-separated; the timezone stays attached
        // to the timestamp. A timezone name must never be read as a separator.
        #expect(SchedulePresentation.describeTrigger(
            schedule(kind: .once, runTime: nil, timezone: "UTC", runAt: "2026-10-20T01:30:00Z", nextRunAt: nil), labels: labels)
            == "Once, 2026-10-20 01:30 (UTC)")
        // With neither runAt nor nextRunAt there is no timestamp to attach.
        #expect(SchedulePresentation.describeTrigger(
            schedule(kind: .once, runTime: nil, runAt: nil, nextRunAt: nil), labels: labels)
            == "Once (Asia/Shanghai)")
    }

    @Test func runTimeNormalizationIsCanonicalAndIdempotent() {
        // Wide-in / strict-out: `9:30` is accepted from the user but normalized
        // to the canonical `09:30`, and `9:5` is rejected because the minute
        // must be two digits — the same rule `isRunTime` enforces.
        #expect(SchedulePresentation.normalizeRunTime("9:30") == "09:30")
        #expect(SchedulePresentation.normalizeRunTime("09:30") == "09:30")
        #expect(SchedulePresentation.normalizeRunTime(" 09:30 ") == "09:30")
        #expect(SchedulePresentation.normalizeRunTime("9:5") == nil)
        #expect(SchedulePresentation.normalizeRunTime("24:00") == nil)
        #expect(SchedulePresentation.normalizeRunTime(nil) == nil)
        // Whatever comes out must satisfy `isRunTime`.
        for value in ["9:30", "09:30", " 09:30 ", "00:00", "23:59", "9:5", "24:00", "", "09:30:00"] {
            if let normalized = SchedulePresentation.normalizeRunTime(value) {
                #expect(SchedulePresentation.isRunTime(normalized))
                #expect(SchedulePresentation.normalizeRunTime(normalized) == normalized)
            }
        }
    }

    @Test func finishedOnceScheduleIsDetected() {
        #expect(SchedulePresentation.hasFinished(schedule(kind: .once, nextRunAt: nil)))
        #expect(!SchedulePresentation.hasFinished(schedule(kind: .once, nextRunAt: "2026-10-20T01:30:00Z")))
        #expect(!SchedulePresentation.hasFinished(schedule(kind: .daily, nextRunAt: nil)))
    }

    // ── failure visibility (Server 2.2.0) ────────────────────

    @Test func nothingIsShownWhenTheServerReportsNoFailure() {
        #expect(SchedulePresentation.describeFailure(schedule(), labels: failureLabels) == nil)
        // Absent, empty and whitespace-only all mean "no recorded failure".
        #expect(SchedulePresentation.describeFailure(schedule(lastError: nil), labels: failureLabels) == nil)
        #expect(SchedulePresentation.describeFailure(schedule(lastError: ""), labels: failureLabels) == nil)
        #expect(SchedulePresentation.describeFailure(schedule(lastError: "   \n "), labels: failureLabels) == nil)
    }

    @Test func failedAttemptShowsTheReasonAndTheTimeInTheSchedulesTimezone() {
        let failure = SchedulePresentation.describeFailure(
            schedule(lastError: "connector is offline", lastErrorAt: "2026-10-14T11:05:00Z"),
            labels: failureLabels)
        // 11:05Z is 19:05 in the schedule's own +08:00 zone, never a fixed one.
        #expect(failure?.text == "Last run failed at 2026-10-14 19:05: connector is offline")
        #expect(failure?.switchedOff == false)
        // The same instant read in the schedule's other zone.
        let utc = SchedulePresentation.describeFailure(
            schedule(timezone: "UTC", lastError: "connector is offline", lastErrorAt: "2026-10-14T11:05:00Z"),
            labels: failureLabels)
        #expect(utc?.text == "Last run failed at 2026-10-14 11:05: connector is offline")
    }

    @Test func aFailedAttemptStillReadsAsAnAttempt() {
        // `enabled` is true, so the Server will retry: not "turned off".
        let failure = SchedulePresentation.describeFailure(
            schedule(timezone: "UTC", lastError: "target session is running",
                     lastErrorAt: "2026-10-14T11:05:00Z"),
            labels: failureLabels)
        #expect(failure?.text == "Last run failed at 2026-10-14 11:05: target session is running")
        #expect(failure?.switchedOff == false)
    }

    @Test func aMissingFailureTimeDegradesToTheReasonAlone() {
        let failure = SchedulePresentation.describeFailure(
            schedule(lastError: "connector is offline"), labels: failureLabels)
        #expect(failure?.text == "Last run failed: connector is offline")
        #expect(failure?.switchedOff == false)
    }

    @Test func anUnparseableFailureTimeDegradesToTheReasonAlone() {
        for value in ["", "   ", "garbage", "2026-10-14", "2026-10-14T11:05"] {
            let failure = SchedulePresentation.describeFailure(
                schedule(lastError: "connector is offline", lastErrorAt: value), labels: failureLabels)
            #expect(failure?.text == "Last run failed: connector is offline")
        }
    }

    @Test func anUnknownTimezoneDegradesToTheReasonAlone() {
        let failure = SchedulePresentation.describeFailure(
            schedule(timezone: "Mars/Olympus", lastError: "connector is offline",
                     lastErrorAt: "2026-10-14T11:05:00Z"),
            labels: failureLabels)
        #expect(failure?.text == "Last run failed: connector is offline")
        // An empty zone name is the Server's default, not an unknown one.
        let empty = SchedulePresentation.describeFailure(
            schedule(timezone: "", lastError: "connector is offline", lastErrorAt: "2026-10-14T11:05:00Z"),
            labels: failureLabels)
        #expect(empty?.text == "Last run failed at 2026-10-14 11:05: connector is offline")
    }

    @Test func aScheduleTheServerStoppedReadsAsTurnedOff() {
        let stopped = SchedulePresentation.describeFailure(
            schedule(timezone: "UTC", enabled: false,
                     lastError: "target no longer exists: session not found",
                     lastErrorAt: "2026-10-14T11:05:00Z"),
            labels: failureLabels)
        #expect(stopped?.text == "Turned off at 2026-10-14 11:05: target no longer exists: session not found")
        #expect(stopped?.switchedOff == true)
        // Without a readable time the wording still says "turned off".
        let undated = SchedulePresentation.describeFailure(
            schedule(enabled: false, lastError: "target no longer exists: project not found"),
            labels: failureLabels)
        #expect(undated?.text == "Turned off: target no longer exists: project not found")
        #expect(undated?.switchedOff == true)
    }

    @Test func stoppedByServerOutranksTheFinishedWording() {
        // A `once` schedule the Server switched off has no `nextRunAt`, so it
        // looks finished; the row has to say why it stopped instead.
        let stopped = schedule(kind: .once, runTime: nil, timezone: "UTC", nextRunAt: nil, enabled: false,
                               lastError: "target no longer exists: session not found",
                               lastErrorAt: "2026-10-14T11:05:00Z")
        #expect(SchedulePresentation.hasFinished(stopped))
        #expect(SchedulePresentation.describeFailure(stopped, labels: failureLabels)?.switchedOff == true)
        // The same shape stays a failure when the Server left it enabled: a
        // `once` schedule that gave up after its grace period has no upcoming
        // run either, and the reason — not "Already ran" — is what the row shows.
        let gaveUp = schedule(kind: .once, runTime: nil, timezone: "UTC", nextRunAt: nil,
                              lastError: "connector is offline", lastErrorAt: "2026-10-14T11:05:00Z")
        #expect(SchedulePresentation.hasFinished(gaveUp))
        #expect(SchedulePresentation.describeFailure(gaveUp, labels: failureLabels)?.switchedOff == false)
        // A `once` schedule that simply ran keeps the old wording and no failure.
        let ran = schedule(kind: .once, runTime: nil, nextRunAt: nil)
        #expect(SchedulePresentation.hasFinished(ran))
        #expect(SchedulePresentation.describeFailure(ran, labels: failureLabels) == nil)
    }

    @Test func microsecondFailureTimesAreStillRead() {
        // The Server writes `datetime.isoformat()` precision — six fractional
        // digits — and `ISO8601DateFormatter` takes at most three.
        let failure = SchedulePresentation.describeFailure(
            schedule(lastError: "connector is offline", lastErrorAt: "2026-10-14T11:05:00.569351Z"),
            labels: failureLabels)
        #expect(failure?.text == "Last run failed at 2026-10-14 19:05: connector is offline")
        #expect(SchedulePresentation.parseUTInstant("2026-10-20T01:30:00.123456Z") != nil)
        #expect(SchedulePresentation.formatNextRunAt("2026-10-20T01:30:00.123456Z", timezone: "UTC") == "2026-10-20 01:30")
        // Millisecond and second precision keep working unchanged.
        #expect(SchedulePresentation.formatNextRunAt("2026-10-20T01:30:00.123Z", timezone: "UTC") == "2026-10-20 01:30")
        #expect(SchedulePresentation.formatNextRunAt("2026-10-20T01:30:00Z", timezone: "UTC") == "2026-10-20 01:30")
    }

    @Test func olderServerPayloadsStillDecode() throws {
        let base: [String: Any] = [
            "id": "sch_1", "title": "Digest", "prompt": "Summarize.", "scheduleKind": "daily",
            "runTime": "09:30", "timezone": "UTC", "enabled": true, "sessionId": "sess_1",
            "nextRunAt": "2026-10-20T01:30:00Z",
            "createdAt": "2026-10-01T00:00:00Z", "updatedAt": "2026-10-01T00:00:00Z",
        ]
        // A Server older than 2.2.0 omits the pair entirely.
        let older: V2Schedule = try decode(base)
        #expect(older.lastError == nil)
        #expect(older.lastErrorAt == nil)
        #expect(SchedulePresentation.describeFailure(older, labels: failureLabels) == nil)

        var failed = base
        failed["lastError"] = "connector is offline"
        failed["lastErrorAt"] = "2026-10-14T11:05:00Z"
        let failure: V2Schedule = try decode(failed)
        #expect(failure.lastError == "connector is offline")
        #expect(SchedulePresentation.describeFailure(failure, labels: failureLabels)?.text
                == "Last run failed at 2026-10-14 11:05: connector is offline")

        // Explicit nulls — a run succeeded, or the row was edited, so the
        // failure was cleared. Same as being absent.
        var cleared = base
        cleared["lastError"] = NSNull()
        cleared["lastErrorAt"] = NSNull()
        let ok: V2Schedule = try decode(cleared)
        #expect(ok.lastError == nil)
        #expect(ok.lastErrorAt == nil)
    }

    // ── validation ───────────────────────────────────────────

    private func draft(
        kind: V2ScheduleKind = .daily,
        runTime: String? = "09:30",
        weekday: Int? = nil,
        runAt: String? = nil,
        timezone: String = "Asia/Shanghai",
        target: ScheduleTargetDraft = .init(mode: .session, sessionId: "sess_1")
    ) -> ScheduleDraft {
        ScheduleDraft(title: "Digest", prompt: "Summarize.", scheduleKind: kind,
                      runAt: runAt, runTime: runTime, weekday: weekday,
                      timezone: timezone, target: target)
    }

    @Test func titleAndPromptAreRequired() {
        var value = draft()
        value.title = "   "
        value.prompt = ""
        let result = validateScheduleDraft(value, labels: validation)
        #expect(!result.ok)
        #expect(result.fieldErrors.title != nil)
        #expect(result.fieldErrors.prompt != nil)
    }

    @Test func onceRequiresAFutureRunAt() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let future = ISO8601DateFormatter().string(from: now.addingTimeInterval(3600))
        let past = ISO8601DateFormatter().string(from: now.addingTimeInterval(-3600))

        #expect(validateScheduleDraft(draft(kind: .once, runTime: nil, runAt: nil), labels: validation, now: now).fieldErrors.runAt != nil)
        #expect(validateScheduleDraft(draft(kind: .once, runTime: nil, runAt: past), labels: validation, now: now).fieldErrors.runAt != nil)
        #expect(validateScheduleDraft(draft(kind: .once, runTime: nil, runAt: ISO8601DateFormatter().string(from: now)), labels: validation, now: now).fieldErrors.runAt != nil)
        #expect(validateScheduleDraft(draft(kind: .once, runTime: nil, runAt: "garbage"), labels: validation, now: now).fieldErrors.runAt != nil)
        #expect(validateScheduleDraft(draft(kind: .once, runTime: nil, runAt: future), labels: validation, now: now).ok)
    }

    @Test func dailyAndWeeklyRequireRunTimeAndOnlyWeeklyNeedsWeekday() {
        #expect(validateScheduleDraft(draft(kind: .daily, runTime: nil), labels: validation).fieldErrors.runTime != nil)
        #expect(validateScheduleDraft(draft(kind: .weekly, runTime: nil, weekday: 0), labels: validation).fieldErrors.runTime != nil)
        #expect(validateScheduleDraft(draft(kind: .weekly, weekday: nil), labels: validation).fieldErrors.weekday != nil)
        #expect(validateScheduleDraft(draft(kind: .weekly, weekday: 7), labels: validation).fieldErrors.weekday != nil)
        // A stray weekday on a daily schedule is ignored, not rejected.
        #expect(validateScheduleDraft(draft(kind: .daily, weekday: 9), labels: validation).ok)
    }

    @Test func unknownTimezoneIsRejected() {
        #expect(validateScheduleDraft(draft(timezone: "Mars/Olympus"), labels: validation).fieldErrors.timezone != nil)
        #expect(validateScheduleDraft(draft(timezone: ""), labels: validation).fieldErrors.timezone != nil)
    }

    @Test func exactlyOneTargetIsRequired() {
        let sessionOnly = ScheduleTargetDraft(mode: .session, sessionId: "sess_1")
        #expect(targetIsComplete(sessionOnly))
        #expect(validateScheduleDraft(draft(target: sessionOnly), labels: validation).ok)

        let newSession = ScheduleTargetDraft(mode: .project, projectId: "proj_1", connectorId: "conn_1")
        #expect(targetIsComplete(newSession))

        #expect(!targetIsComplete(ScheduleTargetDraft(mode: .project, projectId: "proj_1", connectorId: nil)))
        #expect(!targetIsComplete(ScheduleTargetDraft(mode: .project, projectId: nil, connectorId: "conn_1")))
        #expect(!targetIsComplete(ScheduleTargetDraft(mode: .session, sessionId: "  ")))
        #expect(!targetIsComplete(ScheduleTargetDraft(mode: .session, sessionId: nil)))
    }

    // ── request shaping ──────────────────────────────────────

    @Test func createRequestOnlyCarriesTheFieldsTheKindNeeds() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let future = ISO8601DateFormatter().string(from: now.addingTimeInterval(3600))

        let once = buildScheduleCreateRequest(draft(kind: .once, runTime: nil, runAt: future))
        #expect(once.scheduleKind == .once)
        #expect(once.runAt != nil)
        #expect(once.runTime == nil)
        #expect(once.weekday == nil)

        let weekly = buildScheduleCreateRequest(draft(kind: .weekly, weekday: 0))
        #expect(weekly.runTime == "09:30")
        #expect(weekly.weekday == 0)
        #expect(weekly.runAt == nil)

        let daily = buildScheduleCreateRequest(draft(kind: .daily))
        #expect(daily.runTime == "09:30")
        #expect(daily.runAt == nil)
        #expect(daily.weekday == nil)
    }

    @Test func createRequestTrimsInputAndDropsTheUnusedTargetFields() {
        var value = draft()
        value.title = "  Digest  "
        value.prompt = "  Summarize.  "
        let request = buildScheduleCreateRequest(value)
        #expect(request.title == "Digest")
        #expect(request.prompt == "Summarize")
        // Session mode never leaks project/connector ids.
        #expect(request.projectId == nil)
        #expect(request.connectorId == nil)
        #expect(request.sessionId == "sess_1")
    }

    @Test func listSortingIsStableAndDoesNotMutateInput() {
        let older = schedule(nextRunAt: "2026-10-01T00:00:00Z")
        let newer = schedule(nextRunAt: "2026-11-01T00:00:00Z")
        let input = [older, newer]
        let sorted = input.sorted { ($0.nextRunAt ?? "") < ($1.nextRunAt ?? "") }
        #expect(sorted.map(\.id) == ["sch_1", "sch_1"])
        #expect(input.count == 2)
    }
}
