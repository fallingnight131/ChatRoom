# ADR-0249: Terminate V1 Friendships in PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Implement V1 friend removal in the PostgreSQL adapter as one serializable
transaction. Resolve and lock enabled V1-mapped participants from the
authenticated actor UUID and exact target username, then lock the canonical
DIRECT pair, its compatibility mapping, and both membership rows. A first
removal assigns the same database transaction timestamp to both `left_at`
values and touches the conversation. It deletes no durable row.

Two active members are the only first-apply state. Two inactive members behind
the retained valid `FRIENDSHIP` mapping are the exact-retry state and return
duplicate success. A missing pair is `NOT_FRIENDS`; a missing target and self
target keep their typed outcomes. Missing mapping, missing membership, a mixed
active/inactive pair, or an unavailable authenticated actor is inconsistent
authoritative state and rolls back rather than reporting a successful removal.

The retained conversation and mapping are the durable evidence needed to
distinguish an exact retry from a pair that was never friends. A later accepted
request may reactivate the same membership rows. This slice adds no transport
handler or product route.

## Verification

The PostgreSQL integration test covers missing, self, and non-friend rejection;
first and duplicate removal; equal non-null leave timestamps; preservation of
conversation sequence, compatibility mapping, and read cursors; and rollback on
partially active corrupt state. The complete disposable-PostgreSQL gate remains
required.
