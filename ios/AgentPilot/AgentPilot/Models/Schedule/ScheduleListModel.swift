import Foundation
import Observation

/// Observable state for the schedules screen.
///
/// Owns loading, the create form and the in-flight mutations. The pure rules
/// (validation, formatting) live in `SchedulePresentation`; this model only
/// wires them to the API and to SwiftUI state.
@MainActor @Observable
final class ScheduleListModel {
    var schedules: [V2Schedule] = []
    var isLoading = false
    var loadErrorMessage: String?
    var showsCreate = false
    var busyScheduleId: String?
    var deletingSchedule: V2Schedule?

    private let api: V2ScheduleAPI

    init(api: V2ScheduleAPI) {
        self.api = api
    }

    var sortedSchedules: [V2Schedule] {
        schedules.sorted { lhs, rhs in
            // Enabled first, then soonest next run, then newest.
            if lhs.enabled != rhs.enabled { return lhs.enabled }
            let left = lhs.nextRunAt ?? "9999"
            let right = rhs.nextRunAt ?? "9999"
            if left != right { return left < right }
            return lhs.createdAt > rhs.createdAt
        }
    }

    func load() async {
        guard !isLoading else { return }
        isLoading = true
        loadErrorMessage = nil
        defer { isLoading = false }
        do {
            schedules = try await api.list().schedules
        } catch {
            loadErrorMessage = error.localizedDescription
        }
    }

    func create(_ request: V2ScheduleCreateRequest) async throws {
        let response = try await api.create(request)
        replace(response.schedule)
    }

    func setEnabled(_ schedule: V2Schedule, enabled: Bool) async {
        guard busyScheduleId == nil else { return }
        busyScheduleId = schedule.id
        defer { busyScheduleId = nil }
        do {
            let response = try await api.update(schedule.id, V2SchedulePatchRequest(enabled: enabled))
            replace(response.schedule)
        } catch {
            loadErrorMessage = error.localizedDescription
        }
    }

    func delete(_ schedule: V2Schedule) async {
        guard busyScheduleId == nil else { return }
        busyScheduleId = schedule.id
        defer { busyScheduleId = nil }
        do {
            try await api.delete(schedule.id)
            schedules.removeAll { $0.id == schedule.id }
            deletingSchedule = nil
        } catch {
            loadErrorMessage = error.localizedDescription
        }
    }

    private func replace(_ schedule: V2Schedule) {
        if let index = schedules.firstIndex(where: { $0.id == schedule.id }) {
            schedules[index] = schedule
        } else {
            schedules.append(schedule)
        }
    }
}
