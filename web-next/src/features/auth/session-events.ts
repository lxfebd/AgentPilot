import type { StoredSession } from "@/features/auth/types";

export type SessionEvent =
  | { type: "refreshed"; session: StoredSession }
  | { type: "expired" };

type SessionEventListener = (event: SessionEvent) => void;

const listeners = new Set<SessionEventListener>();

export function subscribeSessionEvents(listener: SessionEventListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function emitSessionEvent(event: SessionEvent): void {
  for (const listener of listeners) listener(event);
}
