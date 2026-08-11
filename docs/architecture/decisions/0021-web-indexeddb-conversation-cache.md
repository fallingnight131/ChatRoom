# ADR-0021: Web IndexedDB Conversation Cache

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M2

## Context

The Web client keeps active messages and sequence cursors only in Pinia memory.
A page refresh therefore renders an empty conversation until a server history
round trip completes. M2 requires immediate cached rendering and background
sequence synchronization without turning the already-large chat store into the
durable data layer.

## Decision

- Introduce a small IndexedDB repository owned by `WebClient/src/persistence`.
- Partition every record by authenticated account, conversation kind, and
  conversation identity so different users on one browser never share a key.
- Store only message/attachment metadata and the last applied server cursor;
  attachment bytes remain in the authorized HTTP/object-storage data plane.
- Store a bounded text draft alongside each conversation snapshot. Draft writes
  are debounced by the composer, flushed when it is unmounted or changes
  conversation, and cleared after the user submits the message.
- Bound each conversation snapshot to the newest 500 messages in this first
  slice. Later virtualization and retention work may replace snapshots with
  normalized message rows without changing the store/repository boundary.
- Hydrate the selected room or direct conversation asynchronously, guard against
  stale conversation-switch completions, render the cached snapshot, and then request
  `afterSequence`. Fall back to normal server history when IndexedDB is absent,
  blocked, corrupt, or empty.
- Serialize repository writes so an older asynchronous write cannot overwrite a
  newer snapshot.

## Consequences

Recent room and direct conversations become available immediately after login
and selection across page refreshes, subject to browser storage retention. The
server remains authoritative: every hydrated view synchronizes forward and
reconciles stable message IDs and mutation/deletion events. Authenticated room
and friend lists prune inaccessible snapshots, while live removal responses and
notifications remove the corresponding snapshot immediately.

IndexedDB is not an authentication store and must never contain passwords,
tokens, or signing material. Cache management UI, quota/eviction telemetry,
schema migrations beyond version 1, pending sends, and the Windows SQLite
repository follow as separate M2 slices.

## Rollback

Remove store integration and the repository import. The unused browser database
is inert and the client returns to fetching server history on every selection.
Deleting the database is optional and must not be coupled to rollback.

## Verification

- unit tests cover account/conversation partitioning, message/draft bounds,
  cursor normalization, IndexedDB round trip, pruning, and unavailable-storage
  fallback;
- Web production build verifies browser bundling;
- manual browser verification should cover cached render, forward sync, rapid
  room switching, private/incognito storage denial, and server-authoritative
  replay before this becomes a release gate.
