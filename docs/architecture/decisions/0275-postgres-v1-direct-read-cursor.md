# ADR-0275: Advance V1 Private Read Cursors in PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0274

## Decision

Implement the V1 private-read port as a bounded-retry serializable PostgreSQL
transaction. Resolve the positive FRIENDSHIP mapping only through enabled,
mapped accounts that are both active participants of the mapped DIRECT
conversation. Lock the exact actor member and conversation, then observe
`next_sequence - 1` in the same snapshot.

Advance only that actor's `last_read_sequence` to the greater of its current
cursor and observed high watermark. Require the update predicate to retain the
exact account, conversation, prior cursor, and active membership and to affect
exactly one row. Serialization or compare-and-set races retry in a fresh
transaction up to a fixed bound. Exact repeats return `changed=false` without
writing; access failures remain opaque.

Project `legacyLastReadMessageId` from the newest message by canonical
`conversation_sequence` at or below the resulting cursor, constrained to the
same mapped friendship. An empty conversation returns zero. If the newest
canonical message is not representable in V1, fail the operation instead of
silently reporting an older watermark. Apply the same sequence ordering to
`FRIEND_LIST_RSP.peerLastReadMessageId`; numeric `MAX(legacy_message_id)` is
incorrect because runtime V1 IDs allocate downward.

This adapter emits no transport response or peer notification and does not
activate a route. Rollback removes the unused adapter; existing cursor and
directory behavior remain readable.

## Verification

Disposable PostgreSQL proves zero-to-high-watermark advancement across message
and recall sequences, exact-repeat idempotency, opaque outsider denial, and
one-participant isolation. A friend-directory fixture deliberately maps newer
canonical messages to smaller V1 IDs and proves recovery follows sequence rather
than numeric maximum. The complete PostgreSQL migration and integration gate
passes.
