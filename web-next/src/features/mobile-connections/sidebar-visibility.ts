"use client"

import * as React from "react"
import { safeGet, safeSet } from "@/lib/safe-storage"

const STORAGE_KEY = "aa-mobile-connections-sidebar-visible-v1"
const CHANGE_EVENT = "aa:mobile-connections-sidebar-visibility"

function readVisibility(): boolean {
  if (typeof window === "undefined") return true

  const stored = safeGet(STORAGE_KEY)
  return stored === null ? true : stored !== "false"
}

function subscribe(listener: () => void): () => void {
  window.addEventListener("storage", listener)
  window.addEventListener(CHANGE_EVENT, listener)

  return () => {
    window.removeEventListener("storage", listener)
    window.removeEventListener(CHANGE_EVENT, listener)
  }
}

export function setMobileConnectionsSidebarVisible(visible: boolean): void {
  safeSet(STORAGE_KEY, String(visible))

  window.dispatchEvent(new Event(CHANGE_EVENT))
}

export function useMobileConnectionsSidebarVisibility(): readonly [
  boolean,
  (visible: boolean) => void,
] {
  const visible = React.useSyncExternalStore(subscribe, readVisibility, () => true)
  return [visible, setMobileConnectionsSidebarVisible] as const
}
