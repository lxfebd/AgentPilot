import { existsSync } from "node:fs"
import path from "node:path"
import { pathToFileURL } from "node:url"

// Maps the app's "@/..." alias onto web-next/src so node:test can import
// source modules directly. Without this, any module that imports through the
// alias (as all app code does) fails to load under `node --test`.
const root = path.resolve(import.meta.dirname, "..")
const candidates = ["", ".ts", ".tsx", ".mjs", ".js", "/index.ts", "/index.tsx"]

export async function resolve(specifier, context, nextResolve) {
  if (specifier.startsWith("@/")) {
    const base = path.join(root, "src", specifier.slice(2))
    for (const suffix of candidates) {
      const candidate = base + suffix
      if (existsSync(candidate)) {
        return { url: pathToFileURL(candidate).href, shortCircuit: true }
      }
    }
  }
  return nextResolve(specifier, context)
}