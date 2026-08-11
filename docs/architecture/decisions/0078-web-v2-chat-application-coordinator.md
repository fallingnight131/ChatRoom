# ADR-0078: Web V2 Chat Application Coordinator

- Status: Accepted
- Extended by: ADR-0081
- Date: 2026-08-12
- Related milestone: M3

## Context

The V2 protocol, WebSocket lifecycle, and isolated IndexedDB cache are separate
adapters. Wiring them directly into Vue components or the existing large V1
store would duplicate synchronization and optimistic-delivery rules and make a
staged rollback difficult. In particular, a message ACK can carry a sequence
ahead of messages the client has not synchronized yet.

## Decision

- Add a TypeScript application coordinator depending only on transport and V2
  cache ports. Transport provides cancellable observers; views receive immutable
  snapshots and do not own protocol or persistence state.
- After session establishment, request the first bounded directory page. Load
  further composite-cursor pages only on explicit application demand.
- On conversation selection, render the account/conversation-partitioned cache
  first, then request history after its exact decimal contiguous cursor. Ignore
  stale cache completion after a rapid selection change.
- Continue bounded history pages while `has_more` is true, merge by stable server
  ID or `client_message_id`, retain at most 500 accepted records plus unresolved
  user sends, and persist each authoritative merge.
- Create optimistic UTF-8 text with a stable client ID. Reconcile ACK and protocol
  error only through validated Envelope correlation. Retry a failed send with the
  same client ID.
- An ACK changes the optimistic record to accepted but never advances the
  contiguous history cursor. Only a history page advances that cursor; a later
  at-least-once copy deduplicates with the accepted local record.
- Keep the coordinator unconnected to Vue/Pinia and V1 routing until rollout,
  browser liveness, session-proof custody, and integration tests are decided.

## Consequences

Messaging invariants now have one Web V2 application owner independent of UI and
network implementations. Cached content can render before a round trip and
out-of-order local acceptance cannot create a synchronization hole. Directory
metadata itself is not yet persisted. ADR-0080 resumes a valid session in page
memory and continues active history synchronization; ADR-0081 then recovers
bounded unresolved sends after that synchronization.

## Verification

Deterministic TypeScript tests cover session-to-directory startup, composite
directory pagination, exact cursor hydration above 2^53, multi-page history,
cache-first snapshots, stale-selection suppression, optimistic acceptance,
protocol failure and same-key retry, ACK-then-history deduplication, cursor
non-advancement on ACK, generic authentication rejection, and disposal.

## Rollback

Remove the unreferenced coordinator and transport observer subscription. V1
screens, V2 adapters/cache, Java gateway, and stored V2 cache data remain intact.
