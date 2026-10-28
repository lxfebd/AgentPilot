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
## Generated protocol types

`src/generated/protocol/v1/` holds the TypeScript types compiled from
`contracts/protocol/1.0`. Both clients used to generate their own copy; the
generator now writes here, so there is one artifact instead of two.

- Regenerate from either client: `yarn protocol:generate` (`web-next` or
  `desktop-workbench/renderer`); both scripts resolve the same output directory
  and the same `contracts/` input.
- `yarn protocol:check` (run by CI for both clients) compares the files in this
  directory with what the manifest says they should be.
- The per-client `src/generated/protocol/v1/*.ts` files are one-line re-export
  shims; do not edit them, and do not hand-edit the generated files either.
