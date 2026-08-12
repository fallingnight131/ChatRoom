# ADR-0269: Recall V1 Room Messages Atomically

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0268

## Decision

Implement V1 room text/emoji recall as a bounded-retry serializable PostgreSQL
transaction. Resolve the exact ROOM conversation and ROOM message mapping,
require an enabled mapped actor, and lock the canonical message. A first apply
requires current GROUP membership, message ownership, and database time within
120 seconds of durable acceptance.

Allocate one sequence from the GROUP conversation and insert a typed canonical
`MESSAGE_RECALLED` entry plus recall event in the same transaction. The V021
unique constraint permits at most one recall event per canonical message.
Concurrent serialization or uniqueness races retry in fresh transactions up to
a fixed bound.

An existing recall returns the original mapped room/message identity, mutation
sequence, and occurrence time with `duplicate=true` only when its durable actor
matches the caller. This exact owner retry remains recoverable after the time
window or membership removal and creates no new event. Other active room members
receive ownership rejection; missing/mismatched room resources and non-members
receive opaque room access denial. Rejected work consumes no sequence.

This adapter emits no notification and does not activate a route. Attachment
cleanup remains outside the text/emoji slice. Rollback removes the unused
adapter; durable additive recall entries remain valid.

## Verification

Disposable PostgreSQL proves concurrent first/duplicate convergence, one recall
entry, one sequence allocation, mapped identity preservation, wrong-room and
outsider denial, active non-owner rejection, database-time expiry rejection,
unchanged sequence after rejected work, and exact original-owner retry after all
members leave. Existing migration tests retain V021 entry-kind and one-event
constraints.
