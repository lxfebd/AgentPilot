import assert from "node:assert/strict"
import test from "node:test"
import fs from "node:fs"
import path from "node:path"

/**
 * The schedules page must only reference translation keys that exist.
 *
 * A missing key renders as the raw key string (for example
 * "dashboard.schedules.create") in the UI, which is immediately visible to the
 * user and easy to miss in review. This test keeps the page and the locale
 * files from drifting apart as keys are added.
 */

const rendererRoot = path.resolve(import.meta.dirname, "..")
const pagePath = path.join(rendererRoot, "src/components/pages/schedules-page.tsx")
const localeNames = ["messages/en.json", "messages/zh-CN.json"]

const MAIN_NAMESPACE = "dashboard.schedules"

function flatten(value, prefix = "") {
  const out = new Map()
  for (const [key, child] of Object.entries(value)) {
    const full = prefix ? `${prefix}.${key}` : key
    if (child && typeof child === "object" && !Array.isArray(child)) {
      for (const [k, v] of flatten(child, full)) out.set(k, v)
    } else {
      out.set(full, child)
    }
  }
  return out
}

/** Literal keys passed to the given translation function, e.g. t("title"). */
function keysFor(source, fnName) {
  const pattern = new RegExp(`\\b${fnName}\\(\\s*"([^"]+)"`, "g")
  const found = new Set()
  let match
  while ((match = pattern.exec(source)) !== null) found.add(match[1])
  return found
}

test("every schedules page translation key exists in both locales", () => {
  const source = fs.readFileSync(pagePath, "utf8")
  const mainKeys = keysFor(source, "t")
  const commonKeys = keysFor(source, "tCommon")
  assert.ok(mainKeys.size > 0, "no t() keys found — the regex no longer matches")

  for (const localeName of localeNames) {
    const locale = JSON.parse(
      fs.readFileSync(path.join(rendererRoot, localeName), "utf8"),
    )
    const available = flatten(locale)
    const missing = []
    for (const key of mainKeys) {
      if (!available.has(`${MAIN_NAMESPACE}.${key}`)) {
        missing.push(`${MAIN_NAMESPACE}.${key}`)
      }
    }
    for (const key of commonKeys) {
      if (!available.has(`common.${key}`)) missing.push(`common.${key}`)
    }
    assert.deepEqual(
      missing,
      [],
      `${localeName} is missing these keys used by schedules-page.tsx: ${missing.join(", ")}`,
    )
  }
})

test("both locales define the same schedules keys", () => {
  // A key added to only one locale is missing in the other.
  const [en, zh] = localeNames.map((name) =>
    flatten(JSON.parse(fs.readFileSync(path.join(rendererRoot, name), "utf8"))),
  )
  const schedulesKeys = (map) =>
    [...map.keys()].filter((key) => key.startsWith(`${MAIN_NAMESPACE}.`)).sort()

  assert.deepEqual(
    schedulesKeys(zh),
    schedulesKeys(en),
    "en and zh-CN must define the same dashboard.schedules keys",
  )
})
