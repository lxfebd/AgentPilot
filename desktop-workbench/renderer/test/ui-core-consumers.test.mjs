import assert from "node:assert/strict"
import test from "node:test"
import { fileURLToPath } from "node:url"

import { checkConsumers, checkDependencyFree } from "../../../packages/ui-core/scripts/check-consumers.mjs"

// The shared modules live in packages/ui-core and are consumed here and in
// web-next through one-line shims. Both clients assert the same invariant so a
// stale copy is caught whichever project's CI runs.
const repoRoot = fileURLToPath(new URL("../../..", import.meta.url))

const apps = [
  { name: "web-next", src: fileURLToPath(new URL("../../../web-next/src", import.meta.url)) },
  { name: "renderer", src: fileURLToPath(new URL("../src", import.meta.url)) },
]

test("every shared module is consumed through one identical shim in both clients", () => {
  assert.deepEqual(checkConsumers({ repoRoot, apps }), [])
})

test("packages/ui-core imports nothing but relative paths", () => {
  assert.deepEqual(checkDependencyFree({ repoRoot }), [])
})
