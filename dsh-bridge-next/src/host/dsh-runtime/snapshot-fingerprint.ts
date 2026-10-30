import { createHash } from 'node:crypto'

function canonical(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null'
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, item]) => item !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
  return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${canonical(item)}`).join(',')}}`
}

/** Stable fingerprint of a JSON-shaped snapshot: key order and undefined fields do not matter. */
export function snapshotFingerprint(value: unknown): string {
  try {
    return createHash('sha1').update(canonical(value)).digest('hex').slice(0, 16)
  } catch {
    return ''
  }
}
