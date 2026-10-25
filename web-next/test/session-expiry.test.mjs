import assert from "node:assert/strict"
import test from "node:test"

import {
  authResponseToSession,
  clearStoredSession,
  isSessionExpired,
  loadStoredSession,
  saveStoredSession,
  tokenExpirySeconds,
} from "../src/features/auth/session.ts"

function fakeJwt(payload) {
  const header = btoa(JSON.stringify({ alg: "HS256", typ: "JWT" })).replace(/=+$/, "")
  const body = btoa(JSON.stringify(payload)).replace(/=+$/, "")
  return `${header}.${body}.sig`
}

function stubStorage() {
  const store = new Map()
  globalThis.window = {
    localStorage: {
      getItem: (key) => store.get(key) ?? null,
      setItem: (key, value) => store.set(key, String(value)),
      removeItem: (key) => store.delete(key),
    },
  }
  return store
}

const NOW = Math.floor(Date.now() / 1000)

test("tokenExpirySeconds decodes the JWT exp claim", () => {
  assert.equal(tokenExpirySeconds(fakeJwt({ sub: "u1", exp: NOW + 60 })), NOW + 60)
  assert.equal(tokenExpirySeconds("not-a-jwt"), null)
  assert.equal(tokenExpirySeconds("a.b.c"), null)
  assert.equal(tokenExpirySeconds(fakeJwt({ sub: "u1", exp: "later" })), null)
})

test("isSessionExpired reflects the token expiry", () => {
  const session = { accessToken: fakeJwt({ exp: NOW - 1 }), userId: "u1", role: "admin" }
  assert.equal(isSessionExpired(session), true)
  const fresh = { accessToken: fakeJwt({ exp: NOW + 3600 }), userId: "u1", role: "admin" }
  assert.equal(isSessionExpired(fresh), false)
})

test("loadStoredSession rejects expired entries and accepts valid ones", () => {
  stubStorage()
  const expired = { accessToken: fakeJwt({ exp: NOW - 5 }), userId: "u1", role: "admin" }
  saveStoredSession(expired)
  assert.equal(loadStoredSession(), null)

  const valid = { accessToken: fakeJwt({ exp: NOW + 3600 }), userId: "u1", role: "admin" }
  saveStoredSession(valid)
  const loaded = loadStoredSession()
  assert.ok(loaded)
  assert.equal(loaded.userId, "u1")
  assert.equal(typeof loaded.expiresAt, "string")
})

test("legacy stored sessions without expiresAt are still validated via the JWT", () => {
  stubStorage()
  const legacy = JSON.stringify({ accessToken: fakeJwt({ exp: NOW + 60 }), userId: "u1", role: "admin" })
  globalThis.window.localStorage.setItem("aa.session.v1", legacy)
  const loaded = loadStoredSession()
  assert.ok(loaded)
  assert.equal(loaded.expiresAt, undefined)
  const expiredLegacy = JSON.stringify({ accessToken: fakeJwt({ exp: NOW - 5 }), userId: "u1", role: "admin" })
  globalThis.window.localStorage.setItem("aa.session.v1", expiredLegacy)
  assert.equal(loadStoredSession(), null)
})

test("saveStoredSession persists the token expiry as expiresAt", () => {
  stubStorage()
  const session = { accessToken: fakeJwt({ exp: NOW + 120 }), userId: "u1", role: "member" }
  saveStoredSession(session)
  const raw = globalThis.window.localStorage.getItem("aa.session.v1")
  assert.ok(raw)
  const parsed = JSON.parse(raw)
  assert.ok(parsed.expiresAt)
  assert.equal(new Date(parsed.expiresAt).getTime(), (NOW + 120) * 1000)
})

test("authResponseToSession carries expiresAt and keeps the auth shape", () => {
  const auth = {
    accessToken: fakeJwt({ sub: "u1", exp: NOW + 300 }),
    userId: "u1",
    role: "admin",
  }
  const session = authResponseToSession(auth)
  assert.equal(session.accessToken, auth.accessToken)
  assert.equal(session.userId, "u1")
  assert.equal(session.role, "admin")
  assert.equal(typeof session.expiresAt, "string")
})

test("clearStoredSession removes the entry", () => {
  const store = stubStorage()
  store.set("aa.session.v1", "x")
  clearStoredSession()
  assert.equal(store.has("aa.session.v1"), false)
})
