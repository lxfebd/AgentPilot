import { routing } from "@/i18n/routing"
import { safeGet, safeSet } from "@/lib/safe-storage"

export const LOCALE_STORAGE_KEY = "agent-pilot.locale"
const LOCALE_SOURCE_STORAGE_KEY = "agent-pilot.locale.source"
const LOCALE_COOKIE_NAME = "NEXT_LOCALE"
const LOCALE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365

export type AppLocale = (typeof routing.locales)[number]

export function isAppLocale(value: string | undefined): value is AppLocale {
  return routing.locales.includes(value as AppLocale)
}

export function localeFromPathname(pathname: string): AppLocale | null {
  const firstSegment = pathname.split("/")[1]
  return isAppLocale(firstSegment) ? firstSegment : null
}

export function readStoredLocale(): AppLocale | null {
  const value = safeGet(LOCALE_STORAGE_KEY)
  if (!value || !isAppLocale(value)) return null
  return value
}

export function hasManualStoredLocale() {
  return safeGet(LOCALE_SOURCE_STORAGE_KEY) === "manual"
}

export function writeStoredLocale(locale: AppLocale, options: { manual?: boolean } = {}) {
  safeSet(LOCALE_STORAGE_KEY, locale)
  if (options.manual) {
    safeSet(LOCALE_SOURCE_STORAGE_KEY, "manual")
  } else if (!safeGet(LOCALE_SOURCE_STORAGE_KEY)) {
    safeSet(LOCALE_SOURCE_STORAGE_KEY, "auto")
  }
  writeLocaleCookie(locale)
}

export function detectBrowserLocale(): AppLocale {
  const languages = navigator.languages?.length ? navigator.languages : [navigator.language]
  for (const language of languages) {
    const normalized = language.toLowerCase()
    if (normalized === "zh-cn" || normalized.startsWith("zh-hans") || normalized === "zh") {
      return "zh-CN"
    }
    if (normalized.startsWith("en")) {
      return "en"
    }
  }
  return routing.defaultLocale
}

function writeLocaleCookie(locale: AppLocale) {
  try {
    document.cookie = `${LOCALE_COOKIE_NAME}=${encodeURIComponent(locale)}; path=/; max-age=${LOCALE_MAX_AGE_SECONDS}; SameSite=Lax`
  } catch {
    // Cookies can be unavailable in restricted contexts. Client-side routing still works.
  }
}
