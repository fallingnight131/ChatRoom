# ADR-0260: Persist V1 Direct Recall Atomically

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0259

## Decision

Implement V1 direct recall as a retry-convergent serializable PostgreSQL
transaction. Resolve and lock the canonical message from its positive
FRIENDSHIP mapping and authenticated sender account. Require registered UTF-8
text/emoji compatibility state, mapped actor and peer, and an exact mapped
DIRECT conversation; never accept a peer or conversation identity from the
request.

If the canonical message already has a complete recall event by that actor,
return its original sequence and database event time with `duplicate=true`
before checking current membership or age. Otherwise require all canonical
DIRECT memberships active and compare the message acceptance time to database
transaction time using the 120-second policy. Allocate the next conversation
sequence and insert the typed entry plus recall event in one transaction.
Serialization and uniqueness races retry up to a fixed bound so concurrent
exact requests converge without two events or notifications.

V021 strengthens durable invariants: `message_recall_event` now references a
`MESSAGE_RECALLED` entry specifically and `(conversation_id, message_id)` is
unique. This applies to all canonical recall writers, not only V1. Rollback
requires restoring the pre-V021 database backup; older binaries must not write
the V021 schema until their behavior is verified.

This slice adds no gateway handler or live notification.

## Verification

Disposable PostgreSQL tests prove clean migration/restart, wrong-entry-kind
rejection, concurrent first/duplicate convergence, owner-only behavior,
database-time expiry, one durable event/sequence, and exact retry after
friendship removal. The complete Java/PostgreSQL gate remains required.
