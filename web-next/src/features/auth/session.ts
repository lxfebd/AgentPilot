import type { AuthResponse, StoredSession } from "@/features/auth/types";
import { safeGet, safeRemove, safeSet } from "@/lib/safe-storage";

const SESSION_KEY = "aa.session.v1";

/**
 * Decode the JWT `exp` claim (Unix seconds) from an access token payload.
 * Returns null when the token is not a JWT or has no numeric expiry.
 */
export function tokenExpirySeconds(accessToken: string): number | null {
  const firstDot = accessToken.indexOf(".");
  if (firstDot <= 0) return null;
  const secondDot = accessToken.indexOf(".", firstDot + 1);
  if (secondDot <= firstDot) return null;
  const base64 = accessToken.slice(firstDot + 1, secondDot)
    .replace(/-/g, "+")
    .replace(/_/g, "/");
  try {
    const payload = JSON.parse(atob(base64)) as { exp?: unknown };
    const exp = payload.exp;
    return typeof exp === "number" && Number.isFinite(exp) ? exp : null;
  } catch {
    return null;
  }
}

/** Millis left before the stored session expires; null when unknowable. */
export function sessionExpiresInMs(session: StoredSession): number | null {
  const exp = tokenExpirySeconds(session.accessToken);
  if (exp != null) return exp * 1000 - Date.now();
  if (session.expiresAt) {
    const at = new Date(session.expiresAt).getTime();
    if (Number.isFinite(at)) return at - Date.now();
  }
  return null;
}

export function isSessionExpired(session: StoredSession): boolean {
  const remaining = sessionExpiresInMs(session);
  return remaining != null && remaining <= 0;
}

function withExpiry(session: StoredSession): StoredSession {
  const exp = tokenExpirySeconds(session.accessToken);
  if (exp == null) return session;
  return { ...session, expiresAt: new Date(exp * 1000).toISOString() };
}

export function authResponseToSession(auth: AuthResponse): StoredSession {
  return withExpiry({
    accessToken: auth.accessToken,
    userId: auth.userId,
    role: auth.role,
  });
}

export function loadStoredSession(): StoredSession | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = safeGet(SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<StoredSession>;
    if (!parsed.accessToken || !parsed.userId || !parsed.role) return null;
    const session: StoredSession = {
      accessToken: parsed.accessToken,
      userId: parsed.userId,
      role: parsed.role,
      ...(typeof parsed.expiresAt === "string" ? { expiresAt: parsed.expiresAt } : {}),
    };
    if (isSessionExpired(session)) return null;
    return session;
  } catch {
    return null;
  }
}

export function saveStoredSession(session: StoredSession): void {
  safeSet(SESSION_KEY, JSON.stringify(withExpiry(session)));
}

export function clearStoredSession(): void {
  if (typeof window === "undefined") return;
  safeRemove(SESSION_KEY);
}
