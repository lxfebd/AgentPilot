import assert from "node:assert/strict"
import test from "node:test"

import { ApiClient } from "../src/lib/api/client.ts"
import { ApiError } from "../src/lib/api/errors.ts"
import {
  registerAccessTokenRefresh,
  requestAccessTokenRefresh,
  resetAccessTokenRefresh,
} from "../src/lib/api/refresh.ts"

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  })
}

function stubStorage() {
  const store = new Map()
  globalThis.window = {
    location: { origin: "https://app.example.test", hash: "" },
    localStorage: {
      getItem: (key) => store.get(key) ?? null,
      setItem: (key, value) => store.set(key, String(value)),
      removeItem: (key) => store.delete(key),
    },
  }
  return store
}

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

test("concurrent 401s refresh once and replay both requests with the new token", async () => {
  resetAccessTokenRefresh()
  const authorizations = []
  let refreshCalls = 0
  registerAccessTokenRefresh(async (failedToken) => {
    assert.equal(failedToken, "old-token")
    refreshCalls += 1
    await delay(5)
    return "new-token"
  })
  const client = new ApiClient({
    fetcher: async (_url, init) => {
      const authorization = init.headers.get("authorization")
      authorizations.push(authorization)
      if (authorization === "Bearer old-token") return jsonResponse({ detail: "expired" }, 401)
      return jsonResponse({ ok: true })
    },
  })

  const [first, second] = await Promise.all([
    client.get("/dashboard", { token: "old-token" }),
    client.get("/sessions", { token: "old-token" }),
  ])

  assert.deepEqual(first, { ok: true })
  assert.deepEqual(second, { ok: true })
  assert.equal(refreshCalls, 1)
  assert.equal(authorizations.filter((value) => value === "Bearer old-token").length, 2)
  assert.equal(authorizations.filter((value) => value === "Bearer new-token").length, 2)
  resetAccessTokenRefresh()
})

test("a second 401 after the replay is surfaced instead of retried forever", async () => {
  resetAccessTokenRefresh()
  let refreshCalls = 0
  registerAccessTokenRefresh(async () => {
    refreshCalls += 1
    return "new-token"
  })
  const client = new ApiClient({
    fetcher: async () => jsonResponse({ detail: "still unauthorized" }, 401),
  })

  await assert.rejects(
    client.get("/dashboard", { token: "old-token" }),
    (error) => error instanceof ApiError && error.status === 401 && error.kind === "unauthorized",
  )
  assert.equal(refreshCalls, 1)
  resetAccessTokenRefresh()
})

test("unauthenticated requests never trigger a refresh", async () => {
  resetAccessTokenRefresh()
  let refreshCalls = 0
  registerAccessTokenRefresh(async () => {
    refreshCalls += 1
    return "new-token"
  })
  const client = new ApiClient({
    fetcher: async () => jsonResponse({ detail: "bad credentials" }, 401),
  })

  await assert.rejects(
    client.post("/auth/login", { email: "a@b.c" }, { auth: false }),
    (error) => error instanceof ApiError && error.kind === "unauthorized",
  )
  assert.equal(refreshCalls, 0)
  resetAccessTokenRefresh()
})

test("the refresh endpoint itself is never refreshed recursively", async () => {
  resetAccessTokenRefresh()
  let refreshCalls = 0
  registerAccessTokenRefresh(async () => {
    refreshCalls += 1
    return "new-token"
  })
  const client = new ApiClient({
    fetcher: async () => jsonResponse({ detail: "invalid refresh token" }, 401),
  })

  await assert.rejects(
    client.post("/auth/refresh", { refreshToken: "rotten" }, { auth: false }),
    (error) => error instanceof ApiError && error.status === 401,
  )
  assert.equal(refreshCalls, 0)
  resetAccessTokenRefresh()
})

test("requestAccessTokenRefresh single-flights concurrent callers", async () => {
  resetAccessTokenRefresh()
  let calls = 0
  registerAccessTokenRefresh(async () => {
    calls += 1
    await delay(5)
    return "token"
  })

  const results = await Promise.all([
    requestAccessTokenRefresh("old-token"),
    requestAccessTokenRefresh("old-token"),
    requestAccessTokenRefresh("old-token"),
  ])
  assert.deepEqual(results, ["token", "token", "token"])
  assert.equal(calls, 1)

  resetAccessTokenRefresh()
  assert.equal(await requestAccessTokenRefresh("old-token"), null)
})

test("refreshSession rotates the stored session and reuses it for a stale token", async () => {
  stubStorage()
  resetAccessTokenRefresh()
  const { saveStoredSession, loadStoredSession } = await import("../src/features/auth/session.ts")
  const { refreshSession, registerAuthRefreshRequest } = await import("../src/features/auth/refresh.ts")

  saveStoredSession({
    accessToken: "old-access",
    userId: "u1",
    role: "member",
    refreshToken: "refresh-1",
  })

  let refreshRequests = 0
  registerAuthRefreshRequest(async (refreshToken) => {
    assert.equal(refreshToken, "refresh-1")
    refreshRequests += 1
    return {
      userId: "u1",
      email: null,
      displayName: "Tester",
      emailVerified: true,
      role: "member",
      accessToken: "new-access",
      tokenType: "Bearer",
      serverTime: new Date().toISOString(),
      refreshToken: "refresh-2",
      expiresAt: new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString(),
    }
  })

  const rotated = await refreshSession("old-access")
  assert.equal(rotated.accessToken, "new-access")
  assert.equal(refreshRequests, 1)

  // A request that failed with the previous token reuses the rotation instead of
  // rotating the session again.
  const reused = await refreshSession("old-access")
  assert.equal(reused.accessToken, "new-access")
  assert.equal(refreshRequests, 1)
  assert.equal(loadStoredSession().refreshToken, "refresh-2")
  registerAuthRefreshRequest(null)
})

test("refreshSession clears the session and emits expired when the refresh is rejected", async () => {
  stubStorage()
  resetAccessTokenRefresh()
  const { saveStoredSession, loadStoredSessionForRefresh } = await import("../src/features/auth/session.ts")
  const { refreshSession, registerAuthRefreshRequest } = await import("../src/features/auth/refresh.ts")
  const { subscribeSessionEvents } = await import("../src/features/auth/session-events.ts")

  saveStoredSession({
    accessToken: "old-access",
    userId: "u1",
    role: "member",
    refreshToken: "refresh-1",
  })
  const events = []
  const unsubscribe = subscribeSessionEvents((event) => events.push(event.type))

  registerAuthRefreshRequest(async () => {
    throw new ApiError({ status: 401, detail: "invalid refresh token", kind: "unauthorized" })
  })

  const result = await refreshSession("old-access")
  assert.equal(result, null)
  assert.equal(loadStoredSessionForRefresh(), null)
  assert.deepEqual(events, ["expired"])
  unsubscribe()
  registerAuthRefreshRequest(null)
})
