import Testing
@testable import AgentPilot

/// Plan-timeline coverage for the iOS side, where the project has no test
/// target at all and the port shipped these functions untested.
///
/// Scope note: this file deliberately covers only `latestRevisions`, the one
/// function whose inputs are plain data. `placement` and `isEcho` take
/// `ChatTimelineGroup` / `ChatTimelineRowModel`, and those wrap `V2TimelineItem`
/// plus `TimelineRowStructure`; building them in a test needs the project's own
/// fixtures rather than inline literals. Add those cases once a fixture helper
/// exists (see the bottom of this file for the shape it must produce).
///
/// To use: add an "AgentPilotTests" unit-test target in Xcode, add this file to
/// it, and set its host application to AgentPilot. No simulator-only API is
/// involved, so `xcodebuild test -destination 'platform=macOS'` works too.
struct PlanTimelineTests {
    private func document(_ planId: String, _ revision: Int, createdAt: String? = nil) -> V2PlanDocument {
        V2PlanDocument(
            planId: planId,
            revision: revision,
            contentHash: "sha256:\(planId):\(revision)",
            title: "计划 \(planId)",
            markdown: "# \(planId)\n\n版本 \(revision)",
            createdAt: createdAt
        )
    }

    @Test
    func twoPlansOfOneIdKeepOnlyTheNewestRevision() throws {
        let documents = [
            document("p1", 1, createdAt: "2026-01-01T10:00:00Z"),
            document("p1", 3, createdAt: "2026-01-02T10:00:00Z"),
            document("p2", 1),
        ]
        let latest = PlanTimeline.latestRevisions(documents)
        #expect(latest["p1"] == 3, "the newest revision wins")
        #expect(latest["p2"] == 1, "a plan seen once keeps its revision")
    }

    @Test
    func anEmptyHistoryHasNoLatestRevision() throws {
        #expect(PlanTimeline.latestRevisions([]).isEmpty)
    }
}

// MARK: - Fixture shape the remaining cases need
//
// `placement(_:groups:)` takes [ChatTimelineGroup], whose initializer is
// (kind: ChatTimelineGroup.Kind, rows: [ChatTimelineRowModel]).
// `ChatTimelineRowModel` is a final class initialized from a `V2TimelineItem`
// (init(_ value: V2TimelineItem, animate: Bool = false)) and exposes
// `.value.role`, `.value.createdAt` and `.value.displayText`.
//
// Why this file stops here: a usable fixture needs a `V2TimelineItem`, and that
// struct has 17 fields - including `raw: JSONValue`, `source: JSONValue` and
// `content: V2TimelineItemContent` - with `displayText` derived from `content`
// rather than stored. Hand-writing that literal without a compiler is how a test
// file ends up that does not build, so it is left to be written where it can be
// compiled.
//
// Two practical ways to finish it on a Mac:
//   1. Decode one: build the item as JSON and `JSONDecoder().decode(...)`, which
//      needs only the wire fields the server actually sends.
//   2. Reuse a production fixture if the app has one (a debug-only sample
//      session), which is how the existing web/desktop suites get their rows.
//
// With either in place, these cases port directly from the web/desktop suites:
//   * an approved plan sits directly above the implementation it started
//   * a plan still under review (no later user row) stays at the end
//   * a plan with no createdAt goes to the end rather than being dropped
//   * a system row repeating the plan's markdown is an echo; a user row is not
