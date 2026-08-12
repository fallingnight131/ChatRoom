# ADR-0272: Advance V1 Room Read Cursors in PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0271

## Decision

Implement the V1 room-read port as a bounded-retry serializable PostgreSQL
transaction. Resolve the positive ROOM mapping only through an enabled mapped
actor's active GROUP membership. Lock the exact `(conversation_id, account_id)`
member row and its conversation, then observe `next_sequence - 1` in the same
snapshot.

Advance only that account's `last_read_sequence` to the greater of its current
cursor and observed high watermark. Require the update predicate to retain the
exact account, conversation, prior cursor, and active membership and to affect
exactly one row. A serialization or compare-and-set race retries in a fresh
transaction up to a fixed bound. An exact repeat returns `changed=false` and
does not write. Missing room, disabled/unmapped actor, or inactive membership
returns one opaque access denial.

This adapter emits no transport response or room receipt and does not activate
a route. Rollback removes the unused adapter; prior read cursors remain valid.

## Verification

Disposable PostgreSQL proves zero-to-high-watermark advancement across message
and recall sequences, exact repeat idempotency, wrong-room denial, persisted
cursor value, and one-account isolation in a room with multiple active members.
The first test run intentionally caught and prevented a broad conversation-only
update by retaining the exact-account predicate and one-row invariant.
