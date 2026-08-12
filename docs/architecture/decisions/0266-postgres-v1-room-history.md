# ADR-0266: Read Complete V1 Room History from PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0265

## Decision

Implement the V1 room-history port as one read-only repeatable-read PostgreSQL
transaction. Resolve the positive V1 ROOM mapping only through an enabled mapped
actor's active GROUP membership. Reject a cursor beyond the conversation's
durable high watermark.

Before returning any page, prove that every canonical entry in the room can be
represented by V1: message, recall, and deletion entry children must exist;
messages must be mapped ROOM text/emoji with mapped senders; recalls must target
mapped room messages and remain one per message; deletion events must be mapped
V1 imports with positive signed-32-bit event/message/file IDs, duplicate-free
arrays, and at most 1,000 IDs per array. Unknown entry kinds or partial mappings
fail the whole transaction rather than allowing a cursor to skip incompatible
state.

For sequence mode, derive one ordered item stream from each message's latest
creation/recall sequence plus deletion-event sequences. Apply the 100-item limit
to that combined stream, then separate it into compatible `messages` and
`events` arrays. A continued page ends at its last returned item; a final or
empty page advances to the snapshot high watermark. Latest timestamp mode reads
messages only in ascending creation order after selecting the newest bounded
rows.

This adapter performs no transport writes and does not activate the detached
module. Rollback removes the unused adapter; durable imported and runtime data
remain unchanged.

## Verification

Disposable PostgreSQL verifies active membership and opaque denial, beyond-head
cursor rejection, a first mixed page containing message creation plus folded
recall, a deletion-only tail, exact continuation/high-watermark metadata, and a
latest message-only page. Existing import reconciliation verifies the canonical
and V1 mapping source used by the projection.
