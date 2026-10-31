import assert from "node:assert/strict"
import test from "node:test"
import { registerSource } from "./helpers/onboarding-source.mjs"

const storage = new Map()
globalThis.window = {
  location: { origin: "https://renderer.example" },
  localStorage: {
    getItem: (key) => (storage.has(key) ? storage.get(key) : null),
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: (key) => storage.delete(key),
  },
}

const hooks = registerSource()
const { ApiClient } = await import("../src/lib/api/client.ts")
const { ApiError } = await import("../src/lib/api/errors.ts")
const { authApi } = await import("../src/features/auth/api.ts")
const { loadStoredSession } = await import("../src/features/auth/session.ts")
const {
  installSessionRefresh,
  refreshStoredSession,
  subscribeSessionAuthEvents,
} = await import("../src/features/auth/session-refresh.ts")
hooks.deregister()

const SESSION_KEY = "aa.session.v1"

function storeSession(overrides = {}) {
  window.localStorage.setItem(
    SESSION_KEY,
    JSON.stringify({
      accessToken: "access-1",
      userId: "user-1",
      role: "member",
      serverUrl: "https://server.example",
      refreshToken: "refresh-1",
      ...overrides,
    }),
  )
}

function collectEvents() {
  const events = []
  const unsubscribe = subscribeSessionAuthEvents((event) => events.push(event))
  return { events, unsubscribe }
}

function jsonResponse(payload, status) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json" },
  })
}

function deferred() {
  let release
  const promise = new Promise((resolve) => {
    release = resolve
  })
  return { promise, release }
}

const refreshedOutcome = { status: "refreshed", accessToken: "access-2" }

test("concurrent refreshes share one request", async (t) => {
  storeSession()
  const gate = deferred()
  let calls = 0
  let requestedRefreshToken
  t.mock.method(authApi, "refreshSession", async (refreshToken) => {
    calls += 1
    requestedRefreshToken = refreshToken
    await gate.promise
    return { accessToken: "access-2", refreshToken: "refresh-2", expiresAt: "2026-05-01T00:00:00.000Z" }
  })
  const { events, unsubscribe } = collectEvents()
  const first = refreshStoredSession()
  const second = refreshStoredSession()
  gate.release()
  const outcomes = await Promise.all([first, second])
  assert.equal(calls, 1)
  assert.equal(requestedRefreshToken, "refresh-1")
  assert.deepEqual(outcomes, [refreshedOutcome, refreshedOutcome])
  assert.equal(loadStoredSession()?.accessToken, "access-2")
  assert.equal(loadStoredSession()?.refreshToken, "refresh-2")
  assert.equal(loadStoredSession()?.expiresAt, "2026-05-01T00:00:00.000Z")
  assert.equal(events.filter((event) => event.type === "refreshed").length, 1)
  unsubscribe()
})

test("a rejected refresh token clears the session and reports expiry", async (t) => {
  storeSession()
  t.mock.method(authApi, "refreshSession", async () => {
    throw new ApiError({ status: 401, kind: "unauthorized", detail: "refresh token expired" })
  })
  const { events, unsubscribe } = collectEvents()
  const outcome = await refreshStoredSession()
  assert.deepEqual(outcome, { status: "expired", reason: "rejected" })
  assert.equal(loadStoredSession(), null)
  assert.deepEqual(events, [{ type: "expired", reason: "rejected" }])
  unsubscribe()
})

test("a refresh network failure keeps the stored session", async (t) => {
  storeSession()
  t.mock.method(authApi, "refreshSession", async () => {
    throw new ApiError({ status: 0, kind: "network", detail: "socket closed" })
  })
  const { events, unsubscribe } = collectEvents()
  const outcome = await refreshStoredSession()
  assert.deepEqual(outcome, { status: "network", detail: "socket closed" })
  assert.equal(loadStoredSession()?.refreshToken, "refresh-1")
  assert.deepEqual(events, [])
  unsubscribe()
})

test("a session without a refresh token cannot be rotated", async () => {
  storeSession({ refreshToken: undefined })
  const { events, unsubscribe } = collectEvents()
  const outcome = await refreshStoredSession()
  assert.deepEqual(outcome, { status: "expired", reason: "no-refresh-token" })
  assert.equal(loadStoredSession(), null)
  assert.deepEqual(events, [{ type: "expired", reason: "no-refresh-token" }])
  unsubscribe()
})

test("a refresh cannot resurrect a session cleared while it was in flight", async (t) => {
  storeSession()
  const gate = deferred()
  t.mock.method(authApi, "refreshSession", async () => {
    await gate.promise
    return { accessToken: "access-2", refreshToken: "refresh-2" }
  })
  const pending = refreshStoredSession()
  window.localStorage.removeItem(SESSION_KEY)
  gate.release()
  const outcome = await pending
  assert.deepEqual(outcome, { status: "expired", reason: "rejected" })
  assert.equal(loadStoredSession(), null)
})

test("a 401 rotates the token and replays the request once", async () => {
  const requests = []
  let refreshes = 0
  const client = new ApiClient({
    baseUrl: "https://server.example",
    getToken: () => "access-1",
    refreshAuth: async () => {
      refreshes += 1
      return refreshedOutcome
    },
    fetcher: async (input, init) => {
      requests.push({ url: String(input), auth: new Headers(init.headers).get("authorization") })
      if (requests.length === 1) return jsonResponse({ detail: "expired" }, 401)
      return jsonResponse({ ok: true }, 200)
    },
  })
  const result = await client.get("/sessions")
  assert.deepEqual(result, { ok: true })
  assert.equal(refreshes, 1)
  assert.deepEqual(
    requests.map((request) => request.auth),
    ["Bearer access-1", "Bearer access-2"],
  )
})

test("a second 401 after the replay is not refreshed again", async () => {
  const requests = []
  let refreshes = 0
  const client = new ApiClient({
    baseUrl: "https://server.example",
    getToken: () => "access-1",
    refreshAuth: async () => {
      refreshes += 1
      return refreshedOutcome
    },
    fetcher: async (input, init) => {
      requests.push(new Headers(init.headers).get("authorization"))
      return jsonResponse({ detail: "expired" }, 401)
    },
  })
  await assert.rejects(
    () => client.get("/sessions"),
    (error) => error instanceof ApiError && error.status === 401,
  )
  assert.equal(refreshes, 1)
  assert.deepEqual(requests, ["Bearer access-1", "Bearer access-2"])
})

test("unauthenticated requests never trigger a refresh", async () => {
  const requests = []
  let refreshes = 0
  const client = new ApiClient({
    baseUrl: "https://server.example",
    getToken: () => "access-1",
    refreshAuth: async () => {
      refreshes += 1
      return refreshedOutcome
    },
    fetcher: async (input, init) => {
      requests.push(new Headers(init.headers).get("authorization"))
      return jsonResponse({ detail: "unauthorized" }, 401)
    },
  })
  await assert.rejects(
    () => client.get("/auth/config", { auth: false }),
    (error) => error instanceof ApiError && error.status === 401,
  )
  assert.equal(refreshes, 0)
  assert.deepEqual(requests, [null])
})

test("the refresh endpoint never triggers another refresh", async () => {
  const requests = []
  let refreshes = 0
  const client = new ApiClient({
    baseUrl: "https://server.example",
    getToken: () => "access-1",
    refreshAuth: async () => {
      refreshes += 1
      return refreshedOutcome
    },
    fetcher: async (input, init) => {
      requests.push(new Headers(init.headers).get("authorization"))
      return jsonResponse({ detail: "invalid refresh token" }, 401)
    },
  })
  await assert.rejects(
    () => client.post("/auth/refresh", { refreshToken: "refresh-1" }),
    (error) => error instanceof ApiError && error.status === 401,
  )
  assert.equal(refreshes, 0)
  assert.deepEqual(requests, ["Bearer access-1"])
})

test("an unreachable refresh surfaces as a network error", async () => {
  const client = new ApiClient({
    baseUrl: "https://server.example",
    getToken: () => "access-1",
    refreshAuth: async () => ({ status: "network", detail: "offline" }),
    fetcher: async () => jsonResponse({ detail: "expired" }, 401),
  })
  await assert.rejects(
    () => client.get("/sessions"),
    (error) => error instanceof ApiError && error.status === 0 && error.kind === "network" && error.detail === "offline",
  )
})

test("installSessionRefresh rotates the stored session on a 401", async (t) => {
  storeSession()
  t.mock.method(authApi, "refreshSession", async () => ({
    accessToken: "access-2",
    refreshToken: "refresh-2",
    expiresAt: "2026-06-01T00:00:00.000Z",
  }))
  const requests = []
  const client = new ApiClient({
    baseUrl: "https://server.example",
    getToken: () => loadStoredSession()?.accessToken,
    fetcher: async (input, init) => {
      requests.push(new Headers(init.headers).get("authorization"))
      if (requests.length === 1) return jsonResponse({ detail: "expired" }, 401)
      return jsonResponse({ ok: true }, 200)
    },
  })
  installSessionRefresh(client)
  const result = await client.get("/sessions")
  assert.deepEqual(result, { ok: true })
  assert.deepEqual(requests, ["Bearer access-1", "Bearer access-2"])
  assert.equal(loadStoredSession()?.refreshToken, "refresh-2")
})
