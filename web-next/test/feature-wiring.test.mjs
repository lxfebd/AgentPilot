import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

// The Plan feature was ported twice as files nobody mounted, and the workflow
// and planning capabilities were ported as objects no client read. None of it
// failed typecheck: a file that exists and type-checks can still be dead.
//
// This is the guard for that class. Each row is an observed consumer, not a
// hopeful one - every entry below was verified by hand once, and this test
// keeps it true. An entry that stops being referenced means something dropped
// the wiring again.
const repoRoot = fileURLToPath(new URL("../..", import.meta.url))

const clients = {
  web: `${repoRoot}web-next/src/`,
  desktop: `${repoRoot}desktop-workbench/renderer/src/`,
}

const rows = [
  // The plan panel is the feature's front door on both clients.
  ["web", "components/session-detail.tsx", "PlanningPanel"],
  ["desktop", "components/session-detail.tsx", "PlanningPanel"],
  ["web", "components/session-detail.tsx", "PlanningReviewPanel"],
  ["desktop", "components/session-detail.tsx", "PlanningReviewPanel"],
  // A plan event must reach the timeline, or the panel never sees a plan.
  ["web", "components/session-detail.tsx", "planning.state.updated"],
  ["desktop", "components/session-detail.tsx", "planning.state.updated"],
  // The protocol types live in ui-core; a client copy would be a regression.
  ["web", "generated/protocol/v1/planning-state.ts", "@agentpilot/ui-core"],
  ["desktop", "generated/protocol/v1/planning-state.ts", "@agentpilot/ui-core"],
]

for (const [client, file, token] of rows) {
  test(`${client}: ${file} references ${token}`, () => {
    const source = readFileSync(`${clients[client]}${file}`, "utf8")
    assert.ok(
      source.includes(token),
      `${client}'s ${file} no longer references ${token}: the wiring that made this feature reachable is gone`,
    )
  })
}

// The planning namespace must be nested; flattening it to one key per message
// is how every translation silently resolved to its own key name.
for (const messagesPath of ["web-next/messages/en.json", "desktop-workbench/renderer/messages/en.json"]) {
  test(`${messagesPath}: the planning namespace keeps the keys the UI resolves`, () => {
    const planning = JSON.parse(readFileSync(new URL(`../../${messagesPath}`, import.meta.url), "utf8")).planning
    assert.ok(planning, "the planning namespace is missing")
    for (const key of ["title", "mode", "approve"]) {
      assert.ok(
        Object.hasOwn(planning, key),
        `planning.${key} is missing - a translation now resolves to the raw key`,
      )
    }
  })
}
