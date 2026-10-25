"use client"

/**
 * localStorage wrapper that never throws.
 *
 * Browsers can disable storage entirely (private browsing, blocked
 * third-party cookies, quota). Reads and writes then throw; these wrappers
 * swallow the error and fall back to an in-memory map so feature code can
 * degrade gracefully instead of crashing.
 *
 * window.localStorage is resolved lazily on every call so test harnesses can
 * stub it after import.
 */

const memory = new Map<string, string>()

export function safeGet(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return memory.get(key) ?? null
  }
}

export function safeSet(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // storage unavailable — keep the in-memory fallback
  }
  memory.set(key, value)
}

export function safeRemove(key: string): void {
  try {
    window.localStorage.removeItem(key)
  } catch {
    // storage unavailable — nothing to remove
  }
  memory.delete(key)
}