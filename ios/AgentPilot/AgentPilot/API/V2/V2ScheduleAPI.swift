import Foundation

/// Maps the `/schedules` resource. One method per Server endpoint; no workflow
/// validation and no app-state mutation live here (see `ios/ARCHITECTURE.md`).
struct V2ScheduleAPI {
    let transport: any HTTPTransport

    func list() async throws -> V2ScheduleListResponse {
        try await transport.send(HTTPRequest<EmptyRequestBody, V2ScheduleListResponse>(method: .get, path: "/schedules"))
    }

    func create(_ body: V2ScheduleCreateRequest) async throws -> V2ScheduleResponse {
        try await transport.send(HTTPRequest<V2ScheduleCreateRequest, V2ScheduleResponse>(method: .post, path: "/schedules", body: body))
    }

    func update(_ id: String, _ body: V2SchedulePatchRequest) async throws -> V2ScheduleResponse {
        try await transport.send(HTTPRequest<V2SchedulePatchRequest, V2ScheduleResponse>(method: .patch, path: path(id), body: body))
    }

    func delete(_ id: String) async throws {
        let request = HTTPRequest<EmptyRequestBody, EmptyResponse>(method: .delete, path: path(id))
        _ = try await transport.send(request)
    }

    private func path(_ id: String) -> String { "/schedules/" + id.v2URLPathComponentEncoded }
}
