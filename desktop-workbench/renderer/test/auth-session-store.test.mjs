import assert from "node:assert/strict"
import { test } from "node:test"

const SESSION_KEY = "aa.session.v1"

const {
  loadStoredSession,
  saveStoredSession,
  clearStoredSession,
  updateStoredSessionTokens,
} = await import("../src/features/auth/session.ts")

function withLocalStorage(run) {
  const data = new Map()
  const previous = globalThis.window
  globalThis.window = {
    localStorage: {
      getItem: (key) => (data.has(key) ? data.get(key) : null),
      setItem: (key, value) => data.set(key, String(value)),
      removeItem: (key) => data.delete(key),
    },
  }
  const finish = (result) => {
    if (previous === undefined) delete globalThis.window
    else globalThis.window = previous
    return result
  }
  try {
    return run(data)
  } catch (error) {
    throw finish(error)
  }
}

test("stores and restores the refresh token and expiry alongside the access token", () =>
  withLocalStorage((data) => {
    const session = {
      accessToken: "access-1",
      userId: "user-1",
      role: "admin",
      serverUrl: "https://server.example",
      refreshToken: "refresh-1",
      expiresAt: "2026-02-01T00:00:00.000Z",
    }
    saveStoredSession(session)
    assert.deepEqual(data.get(SESSION_KEY), JSON.stringify(session))
    assert.deepEqual(loadStoredSession(), session)
  }))

test("loads legacy sessions without refresh fields", () =>
  withLocalStorage((data) => {
    data.set(SESSION_KEY, JSON.stringify({ accessToken: "a", userId: "u", role: "member", serverUrl: "https://s" }))
    assert.deepEqual(loadStoredSession(), {
      accessToken: "a",
      userId: "u",
      role: "member",
      serverUrl: "https://s",
      refreshToken: undefined,
      expiresAt: undefined,
    })
  }))

test("updateStoredSessionTokens rotates tokens and keeps user and server identity", () =>
  withLocalStorage((data) => {
    saveStoredSession({
      accessToken: "old-access",
      userId: "user-1",
      role: "member",
      serverUrl: "https://server.example",
      refreshToken: "old-refresh",
      expiresAt: "2026-01-01T00:00:00.000Z",
    })
    const updated = updateStoredSessionTokens({
      accessToken: "new-access",
      refreshToken: "new-refresh",
      expiresAt: "2026-03-01T00:00:00.000Z",
    })
    assert.equal(updated?.accessToken, "new-access")
    assert.equal(updated?.refreshToken, "new-refresh")
    assert.equal(updated?.expiresAt, "2026-03-01T00:00:00.000Z")
    assert.equal(updated?.userId, "user-1")
    assert.equal(updated?.serverUrl, "https://server.example")
    assert.equal(updated?.role, "member")
    // Persisted as well.
    assert.deepEqual(loadStoredSession(), updated)
  }))

test("updateStoredSessionTokens keeps old expiry when the server omits it", () =>
  withLocalStorage(() => {
    saveStoredSession({
      accessToken: "old-access",
      userId: "user-1",
      role: "member",
      refreshToken: "old-refresh",
      expiresAt: "2026-01-01T00:00:00.000Z",
    })
    const updated = updateStoredSessionTokens({ accessToken: "new-access" })
    assert.equal(updated?.refreshToken, "old-refresh")
    assert.equal(updated?.expiresAt, "2026-01-01T00:00:00.000Z")
  }))

test("updateStoredSessionTokens is a no-op after the session was cleared", () =>
  withLocalStorage(() => {
    saveStoredSession({ accessToken: "a", userId: "u", role: "member", refreshToken: "r" })
    clearStoredSession()
    const updated = updateStoredSessionTokens({ accessToken: "new", refreshToken: "new-r" })
    assert.equal(updated, null)
    assert.equal(loadStoredSession(), null)
  }))

test("clearStoredSession removes the persisted session", () =>
  withLocalStorage(() => {
    saveStoredSession({ accessToken: "a", userId: "u", role: "member" })
    clearStoredSession()
    assert.equal(loadStoredSession(), null)
  }))
