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
