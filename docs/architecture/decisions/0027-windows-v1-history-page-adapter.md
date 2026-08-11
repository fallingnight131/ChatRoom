# ADR-0027: Windows V1 History Page Adapter

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M2

## Context

After ADR-0026, `ConversationSyncService` owned cursor and repository state, but
the large Widgets controller still duplicated room/direct V1 history parsing,
authoritative field normalization, page-size assumptions, error handling, and
`hasMore` continuation decisions. Malformed or rejected responses could enter
view code, and a non-advancing continuation cursor could create an unbounded
request loop.

This is a high-risk reconnect and compatibility boundary. The upgraded client
must accept both legacy timestamp pages and additive sequence pages while
keeping server IDs, timestamps, membership, mutations, and sequence authority.

## Decision

- `V1HistoryPageAdapter` is the only Windows boundary that converts V1 room and
  direct history JSON into normalized `Message` pages.
- It recognizes explicit server rejection, requires a valid conversation
  target and message array, enforces the server's 100-item page bound, rejects
  negative cursors, and rejects items whose observed sequence exceeds the
  response continuation cursor.
- Parsed history always has local `accepted` delivery state. Sequence-mode
  messages use the greatest message/mutation/sync sequence supplied by the
  server and preserve room mutation events for deterministic reconciliation.
- Attachment bytes and platform cache writes do not belong to the protocol
  adapter. It retains attachment metadata and the Widgets/platform adapter
  decides thumbnail and download-cache behavior.
- `ConversationSyncService::applyPage` owns cursor advancement and the decision
  to request another page. `hasMore` schedules continuation only when the
  response cursor strictly advances beyond the previous cursor.
- `ChatWindow` remains responsible for V1 request encoding, network dispatch,
  view-model reconciliation, visible-scroll behavior, and media presentation.

## Alternatives Considered

- Keep parsing in the two window handlers: rejected because room/direct drift
  had already produced different field handling and no shared malformed-page
  policy.
- Put QJson parsing in `ConversationSyncService`: rejected because that service
  is transport-neutral and must remain reusable by the future V2 adapter.
- Trust every authenticated server response: rejected because client-side
  bounds and progress guards prevent accidental or compromised-server resource
  loops without weakening server authority.

## Consequences

Room and direct history now share one compatibility policy and headless test
seam. Explicit access failures no longer create invalid models. Continuation
cannot spin on a stalled cursor. `ChatWindow` still contains initial history
request encoding and media/UI projection, which are narrower adapter concerns.

## Migration and Rollback

There is no wire or database migration. Both legacy pages and additive sequence
pages retain their existing meaning. Rolling back restores parsing to
`ChatWindow`; stored snapshots remain compatible.

## Verification

- adapter tests cover room/direct pages, legacy and sequence modes, mutation
  events, sender ownership, attachment normalization, explicit rejection,
  oversized pages, negative cursors, and inconsistent sequence bounds;
- synchronization tests cover advancing, stalled, and legacy page scheduling;
- V1 room/friend reliability smoke tests continue to exercise the producer and
  supported consumer paths;
- the full Qt gate compiles all tests and server/client Release targets on the
  macOS development host; native Windows remains a CI product gate.
