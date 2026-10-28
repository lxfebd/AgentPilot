import fs from "node:fs"
import path from "node:path"

const SHIM = /^export \* from "@agentpilot\/ui-core\/([^"]+)"$/

function walk(dir) {
  const found = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) found.push(...walk(full))
    else found.push(full)
  }
  return found
}

function toPosix(value) {
  return value.split(path.sep).join("/")
}

function codeLines(text) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== "" && !line.startsWith("//"))
}

function normalize(line) {
  return line.endsWith(";") ? line.slice(0, -1) : line
}

export function uiCoreModules(repoRoot) {
  const uiSource = path.join(repoRoot, "packages/ui-core/src")
  return walk(uiSource)
    .filter((file) => file.endsWith(".ts") && path.basename(file) !== "index.ts")
    .map((file) => toPosix(path.relative(uiSource, file)).replace(/\.ts$/, ""))
    .sort()
}

// Each module in packages/ui-core is consumed by both clients through a shim at
// the same in-app path, and that shim holds nothing but the re-export. A grown
// shim or a missing one means the duplication the package removed came back.
export function checkConsumers({ repoRoot, apps, modules = uiCoreModules(repoRoot) }) {
  const uiSource = path.join(repoRoot, "packages/ui-core/src")
  const problems = []
  const shims = new Map()

  for (const modulePath of modules) {
    if (!fs.existsSync(path.join(uiSource, modulePath + ".ts"))) {
      problems.push("packages/ui-core/src/" + modulePath + ".ts is missing")
    }
  }

  for (const app of apps) {
    for (const modulePath of modules) {
      const where = app.name + ":src/" + modulePath + ".ts"
      const file = path.join(app.src, modulePath + ".ts")
      if (!fs.existsSync(file)) {
        problems.push(where + " is missing; both clients must consume " + modulePath + " through a shim")
        continue
      }
      const text = fs.readFileSync(file, "utf8")
      const lines = codeLines(text)
      if (lines.length !== 1) {
        problems.push(where + " must stay a single re-export; it now has " + lines.length + " code lines")
        continue
      }
      const match = normalize(lines[0]).match(SHIM)
      if (!match) {
        problems.push(where + ' must be export * from "@agentpilot/ui-core/<path>"; got: ' + lines[0])
        continue
      }
      if (match[1] !== modulePath) {
        problems.push(where + " re-exports " + match[1] + " instead of " + modulePath)
        continue
      }
      shims.set(app.name + ":" + modulePath, text.replace(/\r/g, "").trim())
    }
  }

  for (const modulePath of modules) {
    const rendered = apps
      .map((app) => shims.get(app.name + ":" + modulePath))
      .filter((value) => value !== undefined)
    if (new Set(rendered).size > 1) {
      problems.push(
        "shims for " + modulePath + " differ between " + apps.map((app) => app.name).join(" and "),
      )
    }
  }

  return problems
}

// The package has no node_modules, so a bare specifier such as react or clsx
// would not resolve from this directory in either client.
export function checkDependencyFree({ repoRoot }) {
  const uiSource = path.join(repoRoot, "packages/ui-core/src")
  const problems = []
  for (const file of walk(uiSource)) {
    if (!/\.tsx?$/.test(file)) continue
    const where = toPosix(path.relative(repoRoot, file))
    const text = fs.readFileSync(file, "utf8")
    for (const match of text.matchAll(/^\s*(?:import|export)[^;\n]*?from\s+"([^"]+)"/gm)) {
      if (!match[1].startsWith(".")) problems.push(where + " imports the bare specifier " + match[1])
    }
    for (const match of text.matchAll(/^\s*import\s+"([^"]+)"/gm)) {
      if (!match[1].startsWith(".")) problems.push(where + " imports the bare specifier " + match[1])
    }
  }
  return problems
}
