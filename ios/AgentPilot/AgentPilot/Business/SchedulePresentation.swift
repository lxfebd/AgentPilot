import Foundation

/// Presentation and local-validation rules for scheduled agent tasks.
///
/// Pure value logic with no UI or transport dependency, so it is unit testable.
/// The Server stays the authority on persistence; these helpers only keep
/// obviously wrong input from reaching it (see `docs/api/schedules.md`).
///
/// Time contract from the Server:
///   - `weekday` is 0 = Monday … 6 = Sunday.
///   - `runTime` is `HH:MM` interpreted in `timezone`.
///   - `runAt` is a UTC ISO-8601 instant and must be in the future for `once`.
///   - A schedule targets exactly one of an existing session (`sessionId`) or a
///     new session (`projectId` + `connectorId`).
enum SchedulePresentation {
    static func weekdayIndex(forWeekday calendarWeekday: Int) -> Int {
        // Gregorian: 1 = Sunday … 7 = Saturday. Server: 0 = Monday … 6 = Sunday.
        (calendarWeekday + 5) % 7
    }

    /// Server `weekday` (0 = Monday) → the symbol used by `DateFormatter`.
    static func weekdaySymbol(_ weekday: Int) -> String? {
        guard (0..<7).contains(weekday) else { return nil }
        return Calendar.current.standaloneWeekdaySymbols[(weekday + 1) % 7]
    }

    static func isValidTimezone(_ name: String?) -> Bool {
        guard let name, !name.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        return TimeZone(identifier: name) != nil
    }

    /// `HH:MM` in 24-hour form. Leading zeros and single-digit hours both count.
    static func isRunTime(_ value: String?) -> Bool {
        guard let value else { return false }
        let pattern = #"^([01]\d|2[0-3]):([0-5]\d)$"#
        return value.range(of: pattern, options: .regularExpression) != nil
    }

    /// Normalise `9:30` / ` 09:30 ` to `09:30`; nil when unparseable.
    ///
    /// The shape must match `isRunTime` exactly — both digits of the minute are
    /// required — so a value the form rejects can never be normalised into one
    /// it would have accepted. `9:5` is therefore nil, not `09:05`.
    static func normalizeRunTime(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespaces)
        let pattern = #"^(\d{1,2}):(\d{2})$"#
        guard let range = trimmed.range(of: pattern, options: .regularExpression),
              let match = try? NSRegularExpression(pattern: pattern).firstMatch(
                in: trimmed, options: [], range: NSRange(range, in: trimmed)),
              match.numberOfRanges == 3 else { return nil }
        let hourString = NSString(string: trimmed).substring(with: match.range(at: 1))
        let minuteString = NSString(string: trimmed).substring(with: match.range(at: 2))
        guard let hour = Int(hourString), let minute = Int(minuteString),
              (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        return String(format: "%02d:%02d", hour, minute)
    }

    static func parseUTInstant(_ value: String?) -> Date? {
        guard let value, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }

    /// The local wall-clock fields of a UTC instant in `timezone`.
    static func zonedComponents(_ instant: Date?, timezone: String) -> DateComponents? {
        guard let instant, let zone = TimeZone(identifier: timezone) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        return calendar.dateComponents([.year, .month, .day, .hour, .minute], from: instant)
    }

    private static func pad(_ value: Int) -> String { String(format: "%02d", value) }

    static func formatDateInput(_ parts: DateComponents) -> String? {
        guard let year = parts.year, let month = parts.month, let day = parts.day else { return nil }
        return "\(pad(year))-\(pad(month))-\(pad(day))"
    }

    static func formatTimeInput(_ parts: DateComponents) -> String? {
        guard let hour = parts.hour, let minute = parts.minute else { return nil }
        return "\(pad(hour)):\(pad(minute))"
    }

    /// `2026-10-20 09:00` in the schedule's own timezone.
    static func formatNextRunAt(_ nextRunAt: String?, timezone: String) -> String? {
        guard let parts = zonedComponents(parseUTInstant(nextRunAt), timezone: timezone),
              let date = formatDateInput(parts), let time = formatTimeInput(parts) else { return nil }
        return "\(date) \(time)"
    }

    struct TriggerSummaryLabels {
        /// `once` — e.g. "Once"
        var once: String
        /// `daily` — e.g. "Every day"
        var daily: String
        /// `weekly` — e.g. "Every {weekday}"
        var weekly: String
        /// Weekday names, Monday first.
        var weekdays: [String]
        /// Wraps the timezone, e.g. "({timezone})"
        var timezoneSuffix: (String) -> String
    }

    /// Human trigger summary, e.g. `Every Monday 09:30 (Asia/Shanghai)`.
    static func describeTrigger(_ schedule: V2Schedule, labels: TriggerSummaryLabels) -> String {
        let zone = schedule.timezone.isEmpty ? "UTC" : schedule.timezone
        let suffix = labels.timezoneSuffix(zone)
        if schedule.scheduleKind == .once {
            let parts = zonedComponents(parseUTInstant(schedule.runAt ?? schedule.nextRunAt), timezone: zone)
            let stamp: String
            if let parts, let date = formatDateInput(parts), let time = formatTimeInput(parts) {
                stamp = "\(date) \(time)"
            } else {
                stamp = schedule.runAt ?? ""
            }
            // Same shape as the Web and Desktop clients:
            // "Once, 2026-10-20 09:30 (Asia/Shanghai)". Only the leading label is
            // comma-separated; the timezone stays attached to the timestamp.
            var pieces = [labels.once]
            if !stamp.isEmpty { pieces.append(stamp) }
            var result = pieces.joined(separator: ", ")
            if !suffix.isEmpty { result += " \(suffix)" }
            return result
        }
        let runTime = isRunTime(schedule.runTime) ? (schedule.runTime ?? "").trimmingCharacters(in: .whitespaces) : nil
        if schedule.scheduleKind == .weekly {
            let head: String
            if let weekday = schedule.weekday, (0..<labels.weekdays.count).contains(weekday) {
                head = labels.weekly.replacingOccurrences(of: "{weekday}", with: labels.weekdays[weekday])
            } else {
                head = labels.weekly
            }
            return [head, runTime, suffix].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        }
        return [labels.daily, runTime, suffix].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
    }

    /// `nextRunAt` for a row; a `once` schedule that already ran has none.
    static func nextRunLabel(_ schedule: V2Schedule, emptyLabel: String) -> String {
        formatNextRunAt(schedule.nextRunAt, timezone: schedule.timezone) ?? emptyLabel
    }

    /// True once a `once` schedule has fired: the Server clears `nextRunAt`.
    static func hasFinished(_ schedule: V2Schedule) -> Bool {
        schedule.scheduleKind == .once && schedule.nextRunAt == nil
    }
}

// ── Create-form validation ──────────────────────────────────

enum ScheduleTargetMode: String, CaseIterable, Sendable {
    case session
    case project
}

struct ScheduleTargetDraft: Hashable, Sendable {
    var mode: ScheduleTargetMode = .session
    var sessionId: String?
    var projectId: String?
    var connectorId: String?
}

struct ScheduleDraft: Hashable, Sendable {
    var title: String = ""
    var prompt: String = ""
    var scheduleKind: V2ScheduleKind = .daily
    var runAt: String?
    var runTime: String?
    var weekday: Int?
    var timezone: String = "UTC"
    var target = ScheduleTargetDraft()
}

struct ScheduleValidationLabels: Hashable, Sendable {
    var titleRequired: String
    var promptRequired: String
    var targetRequired: String
    var runAtRequired: String
    var runAtFuture: String
    var runTimeRequired: String
    var weekdayRequired: String
    var timezoneInvalid: String
}

struct ScheduleFieldErrors: Hashable, Sendable {
    var title: String?
    var prompt: String?
    var target: String?
    var runAt: String?
    var runTime: String?
    var weekday: String?
    var timezone: String?
}

struct ScheduleValidationResult: Hashable, Sendable {
    var ok: Bool { error == nil }
    /// First blocking problem, ready to show under the form.
    var error: String?
    var fieldErrors: ScheduleFieldErrors
}

/// Local mirror of the Server's create rules. `now` is injectable so the
/// "must be in the future" rule is testable without waiting for a clock.
static func validateScheduleDraft(
    _ draft: ScheduleDraft,
    labels: ScheduleValidationLabels,
    now: Date = Date()
) -> ScheduleValidationResult {
    var errors = ScheduleFieldErrors()

    if draft.title.trimmingCharacters(in: .whitespaces).isEmpty { errors.title = labels.titleRequired }
    if draft.prompt.trimmingCharacters(in: .whitespaces).isEmpty { errors.prompt = labels.promptRequired }
    if !targetIsComplete(draft.target) { errors.target = labels.targetRequired }
    if !SchedulePresentation.isValidTimezone(draft.timezone) { errors.timezone = labels.timezoneInvalid }

    if draft.scheduleKind == .once {
        let runAt = draft.runAt?.trimmingCharacters(in: .whitespaces) ?? ""
        if runAt.isEmpty {
            errors.runAt = labels.runAtRequired
        } else if let instant = SchedulePresentation.parseUTInstant(runAt) {
            if instant <= now { errors.runAt = labels.runAtFuture }
        } else {
            errors.runAt = labels.runAtRequired
        }
    } else {
        if !SchedulePresentation.isRunTime(draft.runTime) { errors.runTime = labels.runTimeRequired }
        if draft.scheduleKind == .weekly {
            let weekday = draft.weekday
            let valid = weekday.map { (0..<7).contains($0) } ?? false
            if !valid { errors.weekday = labels.weekdayRequired }
        }
    }

    let firstError = [errors.title, errors.prompt, errors.target, errors.runAt,
                      errors.runTime, errors.weekday, errors.timezone]
        .compactMap { $0 }.first
    return ScheduleValidationResult(error: firstError, fieldErrors: errors)
}

/// Exactly one target: an existing session, or `projectId` + `connectorId`.
func targetIsComplete(_ target: ScheduleTargetDraft) -> Bool {
    if target.mode == .session {
        return !(target.sessionId ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    }
    let project = !(target.projectId ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    let connector = !(target.connectorId ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    return project && connector
}

/// Build the create payload from a validated draft. Only the fields the chosen
/// kind needs are sent, so `weekly` never carries a `runAt` and `once` never
/// carries `runTime`.
func buildScheduleCreateRequest(_ draft: ScheduleDraft) -> V2ScheduleCreateRequest {
    let timezone = draft.timezone.trimmingCharacters(in: .whitespaces).isEmpty
        ? "UTC" : draft.timezone
    var request = V2ScheduleCreateRequest(
        title: draft.title.trimmingCharacters(in: .whitespaces),
        prompt: draft.prompt.trimmingCharacters(in: .whitespacesAndNewlines),
        scheduleKind: draft.scheduleKind,
        timezone: timezone
    )

    if draft.target.mode == .session {
        request.sessionId = draft.target.sessionId?.trimmingCharacters(in: .whitespaces)
    } else {
        request.projectId = draft.target.projectId?.trimmingCharacters(in: .whitespaces)
        request.connectorId = draft.target.connectorId?.trimmingCharacters(in: .whitespaces)
    }

    if draft.scheduleKind == .once {
        request.runAt = SchedulePresentation.parseUTInstant(draft.runAt)?.ISO8601Format()
    } else {
        request.runTime = SchedulePresentation.normalizeRunTime(draft.runTime)
        if draft.scheduleKind == .weekly { request.weekday = draft.weekday }
    }
    return request
}

/// The browser/device IANA timezone, never a hardcoded one.
func currentTimezoneIdentifier() -> String {
    let resolved = TimeZone.current.identifier
    return SchedulePresentation.isValidTimezone(resolved) ? resolved : "UTC"
}
