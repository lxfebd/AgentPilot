# @agentpilot/ui-core

Shared, dependency-free TypeScript helpers consumed by both Next apps:
`web-next` (browser client) and `desktop-workbench/renderer` (Electron renderer).

## Why

These modules were duplicated file-for-file in both projects. The copies were
identical, so every edit had to be made twice and any drift was silent.

## Rules

- No third-party runtime dependencies. If a module needs one, that is a signal
  it does not belong here yet.
- No React, no Next, no Electron imports: this package must stay usable from a
  plain Node/TS context and be testable without a DOM.
- Consumers keep their existing `@/lib/<name>` import path: each project holds a
  re-export shim so call sites did not have to move.

## Consumed via portal

Both projects depend on `portal:../<path>/packages/ui-core` and list the package
in `transpilePackages`, because the exported files are `.ts` sources rather than
a prebuilt `dist`.
## Adding a module

- Put the source under `src/` mirroring the original in-app path, e.g.
  `src/components/session/session-list-order.ts` for
  `components/session/session-list-order.ts`.
- The `./*` export pattern maps any subpath to `./src/*.ts`, so imports use
  `@agentpilot/ui-core/components/session/session-list-order` — there is no
  export entry to maintain per module.
- In each app, replace the old file with a one-line re-export shim:
  `export * from "@agentpilot/ui-core/<original-path-without-ext>";`
- Shims must stay identical across the two apps.
- Only modules whose imports are fully self-contained (relative paths only, no
  `@/` aliases, no bare third-party specifiers) can move here: this package has
  no node_modules, so `react`/`clsx`/etc. do not resolve from this directory.
  Framework-coupled or alias-importing modules stay in-app until the repo gains
  a workspace root.
