import { ApiError } from "@/lib/api/errors"
import { apiClient, type ApiAuthRefreshResult, type ApiClient } from "@/lib/api/client"
import { authApi } from "@/features/auth/api"
import {
  clearStoredSession,
  loadStoredSession,
  updateStoredSessionTokens,
} from "@/features/auth/session"
import type { StoredSession } from "@/features/auth/types"

export type SessionExpiryReason = "rejected" | "no-refresh-token"

export type SessionAuthEvent =
  | { type: "refreshed"; session: StoredSession }
  | { type: "expired"; reason: SessionExpiryReason }

const listeners = new Set<(event: SessionAuthEvent) => void>()

let inFlight: Promise<ApiAuthRefreshResult> | null = null

export function subscribeSessionAuthEvents(
  listener: (event: SessionAuthEvent) => void,
): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function emit(event: SessionAuthEvent): void {
  for (const listener of [...listeners]) {
    try {
      listener(event)
    } catch {
      // A broken subscriber must not break token rotation.
    }
  }
}

export function expireSession(reason: SessionExpiryReason = "rejected"): void {
  clearStoredSession()
  emit({ type: "expired", reason })
}

/**
 * Rotates the stored session tokens. Concurrent callers share one in-flight
 * request so the single-use refresh token is never spent twice.
 */
export function refreshStoredSession(): Promise<ApiAuthRefreshResult> {
  if (!inFlight) {
    inFlight = performRefresh().finally(() => {
      inFlight = null
    })
  }
  return inFlight
}

async function performRefresh(): Promise<ApiAuthRefreshResult> {
  const stored = loadStoredSession()
  if (!stored?.refreshToken) {
    expireSession("no-refresh-token")
    return { status: "expired", reason: "no-refresh-token" }
  }

  const expectedRefreshToken = stored.refreshToken
  try {
    const auth = await authApi.refreshSession(expectedRefreshToken)
    const current = loadStoredSession()
    if (
      !current ||
      current.refreshToken !== expectedRefreshToken ||
      current.userId !== stored.userId
    ) {
      // The session was cleared or replaced while the refresh was in flight
      // (sign-out, or a different sign-in); never resurrect it.
      return { status: "expired", reason: "rejected" }
    }
    const session = updateStoredSessionTokens({
      accessToken: auth.accessToken,
      refreshToken: auth.refreshToken,
      expiresAt: auth.expiresAt,
    })
    if (!session) return { status: "expired", reason: "rejected" }
    emit({ type: "refreshed", session })
    return { status: "refreshed", accessToken: session.accessToken }
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 401 || error.status === 403) {
        expireSession("rejected")
        return { status: "expired", reason: "rejected" }
      }
      // Offline or a gateway failure: keep the session and let the caller
      // retry instead of forcing a re-login.
      return { status: "network", detail: error.detail }
    }
    return {
      status: "network",
      detail: error instanceof Error ? error.message : undefined,
    }
  }
}

/** Wires the API client to the stored-session refresh. Safe to call twice. */
export function installSessionRefresh(client: ApiClient = apiClient): void {
  client.setRefreshAuth(refreshStoredSession)
}
