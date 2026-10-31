import assert from "node:assert/strict"
import test from "node:test"
import { registerSource } from "./helpers/onboarding-source.mjs"

const hooks = registerSource()
const { realtimeBackoffDelay, realtimeFailureKind } = await import("../src/lib/realtime-channel.ts")
const { ApiError } = await import("../src/lib/api/errors.ts")
hooks.deregister()

const always = (value) => () => value

test("backoff doubles up to the 60s cap", () => {
  const delays = [0, 1, 2, 3, 4, 5, 6, 7].map((attempt) =>
    realtimeBackoffDelay(attempt, always(1)),
  )
  assert.deepEqual(delays, [1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000])
})

test("backoff stays within the equal-jitter window", () => {
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const full = Math.min(60_000, 1000 * 2 ** attempt)
    const floorDelay = realtimeBackoffDelay(attempt, always(0))
    const ceilDelay = realtimeBackoffDelay(attempt, always(1))
    assert.equal(floorDelay, full / 2)
    assert.equal(ceilDelay, full)
    const middle = realtimeBackoffDelay(attempt, always(0.5))
    assert.equal(middle, Math.round(full * 0.75))
  }
})

test("backoff never exceeds its cap and clamps negative attempts", () => {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const delay = realtimeBackoffDelay(attempt, always(1))
    assert.ok(delay <= 60_000, `attempt ${attempt} produced ${delay}`)
  }
  assert.equal(realtimeBackoffDelay(-5, always(1)), 1000)
  assert.equal(realtimeBackoffDelay(Number.MAX_SAFE_INTEGER, always(1)), 60_000)
})

function apiError(status) {
  return new ApiError({
    status,
    kind: status === 0 ? "network" : "http",
    detail: `HTTP ${status}`,
  })
}

test("authentication failures stop the channel", () => {
  assert.equal(realtimeFailureKind(apiError(401)), "auth")
  assert.equal(realtimeFailureKind(apiError(403)), "auth")
})

test("transient transport failures back off", () => {
  for (const status of [0, 408, 425, 429, 500, 503]) {
    assert.equal(realtimeFailureKind(apiError(status)), "transient", `status ${status}`)
  }
  assert.equal(realtimeFailureKind(new Error("boom")), "transient")
  assert.equal(realtimeFailureKind("offline"), "transient")
})

test("other HTTP failures stop the channel instead of looping", () => {
  for (const status of [400, 404, 409, 422]) {
    assert.equal(realtimeFailureKind(apiError(status)), "permanent", `status ${status}`)
  }
})
