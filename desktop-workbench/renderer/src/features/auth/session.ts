import type { StoredSession } from "@/features/auth/types";

const SESSION_KEY = "aa.session.v1";

export function loadStoredSession(): StoredSession | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<StoredSession>;
    if (!parsed.accessToken || !parsed.userId || !parsed.role) return null;
    return {
      accessToken: parsed.accessToken,
      userId: parsed.userId,
      role: parsed.role,
      serverUrl: typeof parsed.serverUrl === "string" ? parsed.serverUrl : undefined,
      refreshToken:
        typeof parsed.refreshToken === "string" && parsed.refreshToken
          ? parsed.refreshToken
          : undefined,
      expiresAt:
        typeof parsed.expiresAt === "string" && parsed.expiresAt
          ? parsed.expiresAt
          : undefined,
    };
  } catch {
    return null;
  }
}

/**
 * Applies rotated tokens to the persisted session. No-ops when the stored
 * session has been cleared or replaced in the meantime (e.g. after sign-out),
 * so a late refresh response can never resurrect a dead session.
 */
export function updateStoredSessionTokens(tokens: {
  accessToken: string;
  refreshToken?: string;
  expiresAt?: string;
}): StoredSession | null {
  const stored = loadStoredSession();
  if (!stored) return null;
  const next: StoredSession = {
    ...stored,
    accessToken: tokens.accessToken,
    // Keep the previous values when the server omits them on rotation.
    ...(tokens.refreshToken ? { refreshToken: tokens.refreshToken } : {}),
    ...(tokens.expiresAt ? { expiresAt: tokens.expiresAt } : {}),
  };
  saveStoredSession(next);
  return next;
}

export function saveStoredSession(session: StoredSession): void {
  window.localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearStoredSession(): void {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(SESSION_KEY);
}
