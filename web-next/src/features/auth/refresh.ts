import {
  authResponseToSession,
  clearStoredSession,
  loadStoredRefreshToken,
  loadStoredSessionForRefresh,
  saveStoredSession,
} from "@/features/auth/session";
import { ApiError } from "@/lib/api/errors";
import type { AuthResponse, StoredSession } from "@/features/auth/types";
import { emitSessionEvent } from "@/features/auth/session-events";

/** Network call that rotates the session; injectable so rotation stays testable. */
export type AuthRefreshRequest = (refreshToken: string) => Promise<AuthResponse>;

let authRefreshRequest: AuthRefreshRequest | null = null;

/** Replaces the refresh transport. Pass null to fall back to the bundled AuthApi. */
export function registerAuthRefreshRequest(request: AuthRefreshRequest | null): void {
  authRefreshRequest = request;
}

async function requestAuthRefresh(refreshToken: string): Promise<AuthResponse> {
  if (authRefreshRequest) return authRefreshRequest(refreshToken);
  // 延迟加载：auth/api 依赖浏览器侧模块，轮换逻辑本身应当能在纯 node 测试里运行。
  const { authApi } = await import("@/features/auth/api");
  return authApi.refresh(refreshToken);
}

export function canRefreshSession(): boolean {
  return loadStoredRefreshToken() != null;
}

/**
 * Rotates the stored session using its refresh token. Concurrent callers
 * share one rotation because the ApiClient layer single-flights refresh
 * through this handler. Returns the rotated session, or null when there is
 * nothing to rotate or the refresh failed.
 */
export async function refreshSession(failedAccessToken?: string | null): Promise<StoredSession | null> {
  const stored = loadStoredSessionForRefresh();
  if (!stored) return null;
  if (failedAccessToken && stored.accessToken !== failedAccessToken) {
    // Another request already rotated the session; reuse it without a
    // server round-trip.
    return stored;
  }
  if (!stored.refreshToken) return null;
  try {
    const auth = await requestAuthRefresh(stored.refreshToken);
    const session = authResponseToSession(auth);
    saveStoredSession(session);
    emitSessionEvent({ type: "refreshed", session });
    return session;
  } catch (error) {
    // 401 covers 无效/过期/用户禁用/密码已变更 — 一律清会话并要求重新登录。
    if (error instanceof ApiError && error.kind === "unauthorized") {
      clearStoredSession();
      emitSessionEvent({ type: "expired" });
    }
    return null;
  }
}

/** Handler bound into the ApiClient layer (single-flight). */
export async function refreshAccessTokenHandler(failedToken: string | null): Promise<string | null> {
  return (await refreshSession(failedToken))?.accessToken ?? null;
}
