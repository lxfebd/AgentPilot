import assert from "node:assert/strict"
import test from "node:test"

import { ApiError } from "../src/lib/api/errors.ts"
import {
  REALTIME_BACKOFF_MAX_MS,
  realtimeBackoffDelay,
  realtimeFailureKind,
} from "../src/lib/realtime-channel.ts"

test("retry delays double from one second and cap at sixty seconds", () => {
  const delays = Array.from({ length: 9 }, (_, attempt) =>
    realtimeBackoffDelay(attempt, () => 1),
  )
  // Equal jitter between half and full delay; the exponential term is capped.
  assert.deepEqual(delays, [
    1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000, 60000,
  ])
})

test("jitter stays within half..full so the cap always holds", () => {
  for (const attempt of [0, 3, 6, 12]) {
    const low = realtimeBackoffDelay(attempt, () => 0)
    const high = realtimeBackoffDelay(attempt, () => 0.999)
    assert.ok(low <= high)
    assert.ok(high <= REALTIME_BACKOFF_MAX_MS)
    assert.ok(low >= REALTIME_BACKOFF_MAX_MS / 2 || low >= 500)
  }
  assert.equal(realtimeBackoffDelay(6, () => 0), 30000)
  assert.equal(realtimeBackoffDelay(8, () => 1), 60000)
  // Negative attempts are treated as the first retry; absurd ones stay capped.
  assert.equal(realtimeBackoffDelay(-3, () => 1), 1000)
  assert.equal(realtimeBackoffDelay(999, () => 1), 60000)
})

test("authentication failures stop the channel; transient errors back off", () => {
  const unauthorized = new ApiError({ status: 401, detail: "invalid ticket", kind: "unauthorized" })
  const forbidden = new ApiError({ status: 403, detail: "no access", kind: "forbidden" })
  const transient = new ApiError({ status: 503, detail: "down", kind: "server" })
  const permanent = new ApiError({ status: 422, detail: "bad shape", kind: "validation" })
  assert.equal(realtimeFailureKind(unauthorized), "auth")
  assert.equal(realtimeFailureKind(forbidden), "auth")
  assert.equal(realtimeFailureKind(transient), "transient")
  assert.equal(realtimeFailureKind(permanent), "permanent")
  assert.equal(realtimeFailureKind(new Error("dns")), "transient")
})
