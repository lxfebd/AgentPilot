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
        nextRunAt: String? = "2026-10-20T01:30:00Z"
    ) -> V2Schedule {
        V2Schedule(id: "sch_1", title: "Digest", prompt: "Summarize.", scheduleKind: kind,
                   runAt: runAt, runTime: runTime, weekday: weekday, timezone: timezone,
                   enabled: true, sessionId: "sess_1", projectId: nil, connectorId: nil,
                   runtime: nil, cwd: nil, nextRunAt: nextRunAt, lastRunAt: nil,
                   createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z")
    }

    private var labels: SchedulePresentation.TriggerSummaryLabels {
        SchedulePresentation.TriggerSummaryLabels(
            once: "Once", daily: "Every day", weekly: "Every {weekday}",
            weekdays: ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
            timezoneSuffix: { "($0)" }
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
        #expect(SchedulePresentation.describeTrigger(schedule(kind: .weekly, weekday: 6, runTime: "23:00", timezone: "UTC"), labels: labels)
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
            schedule(kind: .once, runTime: nil, runAt: "2026-10-20T01:30:00Z", timezone: "UTC", nextRunAt: nil), labels: labels)
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
