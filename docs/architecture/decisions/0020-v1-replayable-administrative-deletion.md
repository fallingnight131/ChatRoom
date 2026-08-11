# ADR-0020: V1 Replayable Administrative Message Deletion

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

V1 administrators can delete selected, all, older, or newer room messages. The
server physically removes message rows and emits `DELETE_MSGS_NOTIFY` only to
currently connected members. A client that disconnects before the notification
can retain messages that no longer exist on the server. Delete commands also
have no durable operation identity, and the existing system-message broadcast
is neither an audit record nor part of sequence-based synchronization.

This affects correctness, moderation security, reconnect recovery,
idempotency, and observability. Existing V1 clients must continue to receive
the current response and live notification shapes.

## Decision

- Add `room_message_deletion_events` as durable SQL truth for new
  administrative deletion operations. It records the room, operator ID and
  display-name snapshot, `clientOperationId`, mode, selected IDs or cutoff,
  deleted count, event sequence, and creation time.
- Allocate one event sequence from the existing room high watermark in the same
  transaction that persists the event and physically deletes the selected
  message rows. Message creation, recall mutations, and deletion events share
  one cursor namespace.
- Scope idempotency to the authenticated operator and `clientOperationId`.
  Exact retries return the original outcome without a second deletion event or
  notification. Reusing the key with different command parameters is rejected.
- Extend sequence history compatibly with an optional `events` array. Each
  deletion event exposes `eventType: "messagesDeleted"`, `syncSequence`, mode,
  selected message IDs or cutoff, deleted count, and operator display name.
  `nextSequence` advances across both messages and events.
- Updated Web and Windows clients apply deletion events idempotently to current
  state. Old clients ignore `events` but retain existing online
  `DELETE_MSGS_NOTIFY` behavior. Updated clients with an old server retain live
  behavior but cannot recover a deletion missed while offline.
- File deletion remains an idempotent post-commit compensation. The durable
  event is the source of truth if process failure occurs between the SQL commit
  and local/COS object cleanup.
- The table is deliberately deletion-specific rather than a generic event log;
  future edit, reaction, or moderation events require their own reviewed model.

## Alternatives Considered

- **Soft-delete messages in place:** rejected because old clients would render
  redacted file/text rows inconsistently, and it silently changes history from
  message history into mixed message/tombstone data.
- **Keep only live notifications:** rejected because reconnect cannot recover
  missed moderation state and command retries remain ambiguous.
- **One tombstone row per deleted message:** rejected for bulk deletion because
  it expands one administrator action into unbounded sequence allocation and
  response volume.
- **Generic room event table now:** deferred until more event families justify
  the abstraction and its retention/ordering policy.

## Consequences

New administrative deletions become restart-safe, retry-safe, auditable, and
recoverable with the existing room cursor. The event table retains moderation
metadata after message bodies are removed and adds one indexed row per delete
operation. Selected-ID payloads must be bounded to keep database rows and
protocol responses bounded.

Events created before this migration cannot be reconstructed because the
physically deleted message IDs, mode, cutoff, and operator were not durably
stored. The migration therefore starts complete replay coverage at deployment
time rather than fabricating historical audit data.

## Migration and Rollback

1. Expand with the empty table and indexes; runtime behavior remains unchanged.
2. Add transactional writes and dual live/history reads while keeping existing
   V1 responses and notifications.
3. Upgrade Web and Windows consumers and observe duplicate/reconnect outcomes.
4. Retain the table through the V1 compatibility window.

Rollback before step 2 simply leaves an unused empty table. After events are
written, an older server can still operate because it ignores the table, but
deletions performed during rollback are not replayable. Restoring full replay
continuity requires returning to the new server before accepting more delete
commands or restoring the database to the rollback point.

## Verification

- clean and restarted schema equality plus query-plan use of both indexes;
- administrator authorization and selected-ID bounds;
- exact retry, conflicting retry, and process-restart outcome recovery;
- selected/all/before/after deletion and correct file cleanup metadata;
- offline history replay, mixed message/event pagination, deletion-only empty
  pages, and high-watermark monotonicity;
- Web and Windows idempotent event application;
- old-client/new-server live notification compatibility.
