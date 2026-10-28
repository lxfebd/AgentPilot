import assert from "node:assert/strict"
import test from "node:test"
import { fileURLToPath } from "node:url"

import { checkConsumers, checkDependencyFree } from "../../packages/ui-core/scripts/check-consumers.mjs"

// packages/ui-core has no node_modules and the repository has no workspace root,
// so nothing but these shims keeps its modules shared: every module needs a
// one-line re-export at the same path in both clients, and the package itself may
// not reach for a bare specifier. Without this guard a shim can quietly grow back
// into a local copy and the duplication returns invisibly.
const repoRoot = fileURLToPath(new URL("../..", import.meta.url))

const apps = [
  { name: "web-next", src: fileURLToPath(new URL("../src", import.meta.url)) },
  {
    name: "renderer",
    src: fileURLToPath(new URL("../../desktop-workbench/renderer/src", import.meta.url)),
  },
]

test("every shared module is consumed through one identical shim in both clients", () => {
  assert.deepEqual(checkConsumers({ repoRoot, apps }), [])
})

test("packages/ui-core imports nothing but relative paths", () => {
  assert.deepEqual(checkDependencyFree({ repoRoot }), [])
})
