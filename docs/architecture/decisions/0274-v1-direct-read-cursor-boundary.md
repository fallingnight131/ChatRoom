# ADR-0274: Define V1 Private Read-Cursor Advancement

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define transport-independent V1 private read intent from a server-bound
authenticated actor and positive signed-32-bit V1 friendship ID. Do not accept
a client-selected account, peer, message ID, sequence, timestamp, or notification
audience.

A future PostgreSQL adapter must resolve the mapped DIRECT conversation through
the actor's active membership, observe the durable high watermark in the same
transaction, and monotonically advance only that participant's canonical
`last_read_sequence`. Return previous/resulting sequences, changed state, mapped
peer identity, and a compatible `legacyLastReadMessageId`.

Derive that V1 last-read message identity by choosing the newest mapped message
according to canonical creation sequence at or below the resulting cursor. Do
not use numeric maximum: imported IDs may be arbitrary and runtime V1 message
IDs allocate downward. A cursor containing only non-message mutations after the
last creation therefore retains the most recent creation's V1 ID; an empty
conversation returns zero.

Successful marks may later publish the existing `FRIEND_READ_NOTIFY` to the
mapped process-local peer, including exact repeats; consumers apply its
watermark monotonically and `FRIEND_LIST_RSP.peerLastReadMessageId` is durable
restart recovery. Missing/mismatched friendship and inactive membership are one
opaque denial. This slice adds no PostgreSQL adapter, handler, or route.

## Verification

Application tests prove authenticated actor propagation, positive friendship
validation, exact mapped identity, monotonic cursor/changed invariants, bounded
legacy message ID, mapped peer requirements, and persistence identity-substitution
rejection.
