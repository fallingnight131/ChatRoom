# ADR-0026: Windows Conversation Synchronization Service Boundary

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M2

## Context

Windows cached rendering and sequence resume worked, but `ChatWindow` owned two
parallel cursor maps and directly coordinated snapshot hydration, monotonic
cursor advancement, message/page persistence, conversation removal, account
cache clearing, and provisional direct-key promotion. This duplicated room and
direct behavior in a Widgets controller and made cursor correctness difficult
to test without the GUI.

The change affects reconnect correctness, local durability, account isolation,
maintainability, and future background synchronization. Server sequence and
membership authority and the V1 wire format are unchanged.

## Decision

- `ConversationSyncService` owns the active account's room/direct cursor high
  watermarks and coordinates the local conversation repository.
- The service exposes transport-neutral operations for hydrate, monotonic
  advance, single-message upsert, authoritative snapshot replacement,
  conversation removal, cursor forgetting/promotion, and safe account-cache
  clearing.
- Conversation identity is `(kind, stable key)`. Room keys are room IDs; direct
  keys are friendship IDs, with a temporary `peer:<username>` key promoted when
  the first authoritative friend list arrives.
- Repository absence remains an online-only fallback. Persistence failures are
  returned as diagnostics and never manufacture server authority.
- `ChatWindow` initially continued to parse V1 JSON and schedule continuation.
  ADR-0027 subsequently moved response normalization to a V1 adapter and page
  progress decisions into this service; the window still encodes and dispatches
  requests and reconciles the visible `MessageModel`.
- Legacy messages with a null Qt `clientMessageId` are normalized to an empty
  SQL string at the repository boundary so old compatible history cannot violate
  the local `NOT NULL` constraint.

## Alternatives Considered

- Keep cursor maps in `ChatWindow`: rejected because persistence and reconnect
  rules would remain coupled to Widgets and duplicated by conversation type.
- Make the service own `MessageModel`: rejected because a Qt presentation model
  is not durable synchronization state and would prevent headless tests.
- Move V1 JSON parsing into the service now: rejected because it would leak the
  compatibility transport schema into the application boundary.

## Consequences

Room and direct cursors now share one tested monotonic policy, cache clearing
cannot leave stale in-memory cursors, and repository operations have a common
diagnostic boundary. With ADR-0027 the service also rejects stalled `hasMore`
continuation, while the window retains request dispatch and view reconciliation;
the M2 extraction is not yet the final asynchronous sync engine.

## Migration and Rollback

There is no schema or wire migration. Existing snapshot cursors hydrate into the
service on first conversation access. Account-ID migration preserves in-memory
cursor state while switching the repository/account context. Rollback restores
the two window cursor maps and continues to read the same schema-1 database.

## Verification

- unit tests cover restart hydration, monotonic advancement, cursor persistence,
  provisional-to-stable promotion, safe cache clearing, draft/pending retention,
  and online-only fallback;
- repository tests cover null legacy client IDs and existing account isolation;
- the complete Qt gate runs all client tests and compiles server/client Release
  targets on the macOS development host;
- native Windows compilation remains enforced by Windows CI, with clean-host
  product validation deferred to M4.
