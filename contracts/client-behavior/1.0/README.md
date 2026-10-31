# Client Behavior 1.0 Contract (draft)

Status: **draft — not enforced yet.** Nothing in this directory is checked by CI; the
conformance matrix in §8 is the checklist to turn it into a gate.

Client Behavior is the contract between AgentPilot clients (Web, Desktop renderer,
Android, iOS) about *behavior*, not about API shapes. API fields and realtime events
belong to `contracts/protocol/1.0`; Server-to-Connector RPC belongs to
`contracts/runtime-control/2.0`. This contract exists because those two are already
single-source, while the same client-side state machine is currently re-implemented
once per client, and the copies drift.

## 1. What is shared and what is not

Shared (this contract):

- session lifecycle states and the transitions each client may drive;
- approval request/decision semantics, including idempotency;
- auth: token lifecycle, PKCE, callback parsing, URL normalization, legacy token migration;
- error classes and what is safe to retry;
- what may be cached offline and which side wins on conflict.

Not shared: visual design, component tree, routing, platform storage APIs. Each
client keeps its own shell.

## 2. Authority

Three authorities must stay separate:

| Authority | Owns | Never decides |
| --- | --- | --- |
| Server | session records, timeline, approval queue, account state | what a device can reach on its disk |
| Device (Connector) | runtime processes, files, terminals, credentials | who is allowed to see a session |
| Human | approval verdicts | any derived state |

A client never invents state: it renders Server state, and reports device-local facts
(e.g. "connector offline") as facts, not as state transitions.

## 3. Session lifecycle

Canonical states: `draft`, `starting`, `running`, `awaiting_approval`, `idle`,
`failed`, `archived`.

Invariants:

1. A client may request at most `draft|idle → starting`; every other transition is
   Server-driven and must arrive through the realtime channel or a re-fetch.
2. `archived` is terminal for user actions: unarchiving is a separate explicit action.
3. A client that reconnects must re-fetch the session before rendering it; cached
   `running` state is never trusted past the reconnect boundary.
4. `awaiting_approval` is only enterable by an approval request; it cannot be set by
   a client "to be safe".

## 4. Approval semantics

1. A decision is `approve | deny` and is addressed by `approvalId`, never by session id.
2. Decisions are **idempotent**: deciding the same `approvalId` twice returns the
   first result and must not execute a second time. Clients must not rely on
   "the second click wins".
3. Unknown `approvalId` (already resolved or expired) is a distinct outcome from an
   error: the client shows the resolved state, not a retry prompt.
4. Disconnect does not resolve an approval. It stays pending until the Server decides
   it expired, and expiry is visible in the timeline.
5. The decision must be durably queued by the client before the UI acknowledges it;
   "sent" means "accepted locally", not "delivered".

## 5. Auth and token lifecycle

Invariants (all are testable without a network):

1. PKCE: `code_challenge = BASE64URL(SHA256(verifier))`, verifier is 43–128 chars
   from the unreserved set. The challenge method is always `S256`; `plain` is not allowed.
2. Callback parsing accepts exactly: matching `state`, present `code`, no fragment.
   `error=access_denied` maps to a cancellation, not an error dialog.
3. URL normalization: an explicit `https` origin is kept; a bare host gets `https`
   except for LAN/loopback hosts with a port, which get `http`; a trailing `/api/v2`
   path is stripped; userinfo, query and fragment are rejected.
4. WebView login must not be a general browser: same-origin `http(s)` navigation stays
   in the WebView, any other `http(s)` origin opens the system browser, and non-HTTP
   schemes are rejected. `mixedContentMode` stays at `NEVER_ALLOW`.
5. Legacy token migration is atomic: decrypt legacy → write encrypted store → only then
   delete legacy. A failure at any step leaves the previous store intact and usable.
6. Tokens are never written to logs, crash reports, or clipboard.

## 6. Error semantics

| Class | Retry | Client behavior |
| --- | --- | --- |
| transport / 5xx | yes, with backoff | keep state, show offline banner |
| 401 | once, after refresh | then force re-auth |
| 409 conflict | yes, after re-fetch | never blind-retry |
| other 4xx | no | show the server message key |

User-visible strings come from stable keys, not from raw server prose; the same
failure must read the same on every client.

## 7. Offline and caching

- Allowed to cache: session list, last timeline page, approval queue snapshot.
- Never cache: approval verdicts (they are acts, not state), credentials, file contents.
- On reconnect the Server wins for all shared state; device-local facts are re-derived.
- An action performed offline is either queued with a durable idempotency key or
  rejected at the UI. Silent dropping is not allowed.

## 8. Conformance matrix (the gate to build)

Every client must have tests for these rows; `—` means the platform does not have the
surface. Current state is the honest starting point, not a plan.

| Item | Web | Desktop | Android | iOS |
| --- | --- | --- | --- | --- |
| PKCE S256 + verifier length | ? | ? | ✓ | ? |
| callback parsing (state/code/error) | ? | ? | ✓ | ? |
| URL normalization | ? | ? | ✓ | ? |
| WebView navigation allowlist | — | — | ✓ | ? |
| legacy token migration | — | ? | ✗ | ? |
| approval idempotency | ? | ? | ? | ? |
| offline queue / reconnect re-fetch | ? | ? | ? | ? |
| error class → key mapping | ? | ? | ? | ? |

`?` means unverified in this draft. Filling this table is step 7 of the refactor plan.

## 9. Version matrix

App versions are not contract versions. A client may ship a patch without touching
shared behavior; a change to any invariant above is a contract change and bumps this
directory (`1.0` → `1.1`), regardless of app version.

| Client | App version | Contract 1.0 |
| --- | --- | --- |
| Web (`web-next`) | 2.4.0 | partial |
| Desktop (`desktop-workbench`) | 2.4.0 | partial |
| Android | 2.3.2 (master) / 2.3.4 (release branch) | partial |
| iOS | — | unknown |

## 10. Change process

1. Change this document first; a behavior change without a diff here is a bug.
2. Update §8 with the test that proves the new invariant.
3. Only then port the change to clients, one PR per client.
4. Do not encode platform-specific workarounds into this document; note them in the
   client and link back.
