import { isTransientHttpStatus } from "@agentpilot/ui-core/lib/retry"
import { ApiError } from "@/lib/api/errors"

export const REALTIME_BACKOFF_BASE_MS = 1000
export const REALTIME_BACKOFF_MAX_MS = 60_000

export type RealtimeFailureKind = "auth" | "transient" | "permanent"

/**
 * Exponential backoff with equal jitter, capped at 60s. `attempt` counts
 * consecutive failed connects; a successful open resets it to 0.
 */
export function realtimeBackoffDelay(
  attempt: number,
  random: () => number = Math.random,
): number {
  const exponential = Math.min(
    REALTIME_BACKOFF_MAX_MS,
    REALTIME_BACKOFF_BASE_MS * 2 ** Math.max(0, Math.floor(attempt)),
  )
  const half = exponential / 2
  return Math.round(half + random() * half)
}

/**
 * Decides what a failed realtime attempt means: a confirmed authentication
 * failure must stop the channel, transient transport states back off, and
 * anything else is treated as permanent (no reconnect loop).
 */
export function realtimeFailureKind(error: unknown): RealtimeFailureKind {
  if (error instanceof ApiError) {
    if (error.status === 401 || error.status === 403) return "auth"
    if (isTransientHttpStatus(error.status)) return "transient"
    return "permanent"
  }
  // Unknown transport errors (DNS, TLS, gateways) are usually transient.
  return "transient"
}
