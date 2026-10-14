import Foundation

/// A scheduled or recurring agent task.
///
/// The Server fires `prompt` into either an existing session (`sessionId`) or a
/// brand-new session (`projectId` + `connectorId`) at the scheduled moment.
/// Added in 2.1.0; see `docs/api/schedules.md`.
nonisolated struct V2Schedule: Codable, Identifiable, Hashable {
    let id: String
    let title: String
    let prompt: String
    let scheduleKind: V2ScheduleKind
    /// UTC ISO-8601 instant; only set for `once`.
    let runAt: String?
    /// `HH:MM` interpreted in `timezone`; only set for `daily` / `weekly`.
    let runTime: String?
    /// 0 = Monday … 6 = Sunday; only set for `weekly`.
    let weekday: Int?
    let timezone: String
    let enabled: Bool
    let sessionId: V2SessionID?
    let projectId: String?
    let connectorId: V2ConnectorID?
    let runtime: String?
    let cwd: String?
    /// UTC ISO-8601 of the next trigger; null once a `once` schedule has run.
    let nextRunAt: String?
    let lastRunAt: String?
    let createdAt: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case id, title, prompt, scheduleKind, runAt, runTime, weekday
        case timezone, enabled, sessionId, projectId, connectorId, runtime, cwd
        case nextRunAt, lastRunAt, createdAt, updatedAt
    }
}

nonisolated enum V2ScheduleKind: String, Codable, Hashable, CaseIterable, Sendable {
    /// One-shot: needs a future `runAt`.
    case once
    /// Every day at `runTime`.
    case daily
    /// Every week on `weekday` at `runTime`.
    case weekly
}

struct V2ScheduleListResponse: Decodable, Hashable {
    let schedules: [V2Schedule]
    let serverTime: String
}

/// Create / patch / delete all answer with the affected schedule.
struct V2ScheduleResponse: Decodable, Hashable {
    let schedule: V2Schedule
    let serverTime: String
}

struct V2ScheduleCreateRequest: Encodable, Hashable {
    let title: String
    let prompt: String
    let scheduleKind: V2ScheduleKind
    var sessionId: String? = nil
    var projectId: String? = nil
    var connectorId: String? = nil
    var runtime: String? = nil
    var cwd: String? = nil
    var runAt: String? = nil
    var runTime: String? = nil
    var weekday: Int? = nil
    var timezone: String = "UTC"
    var enabled: Bool = true

    enum CodingKeys: String, CodingKey {
        case title, prompt, scheduleKind, sessionId, projectId, connectorId
        case runtime, cwd, runAt, runTime, weekday, timezone, enabled
    }
}

struct V2SchedulePatchRequest: Encodable, Hashable {
    var title: String? = nil
    var prompt: String? = nil
    var scheduleKind: V2ScheduleKind? = nil
    var sessionId: String? = nil
    var projectId: String? = nil
    var connectorId: String? = nil
    var runtime: String? = nil
    var cwd: String? = nil
    var runAt: String? = nil
    var runTime: String? = nil
    var weekday: Int? = nil
    var timezone: String? = nil
    var enabled: Bool? = nil

    enum CodingKeys: String, CodingKey {
        case title, prompt, scheduleKind, sessionId, projectId, connectorId
        case runtime, cwd, runAt, runTime, weekday, timezone, enabled
    }
}
