import type { PublicAnnouncement } from "./types"
import { safeGet, safeSet } from "@/lib/safe-storage"

export const ANNOUNCEMENT_READ_KEY = "aa.announcement.read.v1"
export const ANNOUNCEMENT_READ_EVENT = "aa:announcement-read"
let memoryReadAt: string | null = null

function timestamp(value: string | null): number {
  const parsed = value ? Date.parse(value) : NaN
  return Number.isFinite(parsed) ? parsed : 0
}

export function isAnnouncementUnread(announcement: PublicAnnouncement | null, readAt: string | null): boolean {
  return Boolean(announcement?.markdown.trim() && timestamp(announcement.publishedAt) > timestamp(readAt))
}

export function announcementPageEnabled(page: string | null): boolean {
  return page === "login" || page === "register" || page === "app"
}

export function readAnnouncementTimestamp(): string | null {
  if (typeof window === "undefined") return null
  const stored = safeGet(ANNOUNCEMENT_READ_KEY)
  if (timestamp(stored) > timestamp(memoryReadAt)) memoryReadAt = stored
  return memoryReadAt
}

export function markAnnouncementRead(publishedAt: string): void {
  if (typeof window === "undefined" || !timestamp(publishedAt)) return
  if (timestamp(publishedAt) <= timestamp(readAnnouncementTimestamp())) return
  memoryReadAt = publishedAt
  safeSet(ANNOUNCEMENT_READ_KEY, publishedAt)
  window.dispatchEvent(new Event(ANNOUNCEMENT_READ_EVENT))
}
