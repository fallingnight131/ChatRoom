# ADR-0253: Persist V1 Direct Messages Atomically in PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Implement V1 direct text/emoji submission in one serializable PostgreSQL
transaction. Resolve an enabled, V1-mapped target and require the authenticated
sender/device plus both active members of the mapped canonical DIRECT
conversation. Lock the conversation before allocating its next sequence.

First submission inserts the conversation entry, canonical message, and a
collision-checked descending V1 `FRIENDSHIP` message mapping before commit.
Text maps to canonical message type 1 and emoji to type 2 while preserving the
UTF-8 payload. An exact `(sender, clientMessageId)` retry must match conversation,
device, type, bytes, and digest and must find the same V1 mapping; it returns the
original IDs, sequence, and database timestamp. Any mismatch is an idempotency
conflict. A canonical message missing its mapping is inconsistent state and
fails closed.

Authorization is checked before idempotency projection so a removed friendship
cannot use retries to access message metadata. Serializable or uniqueness races
retry in fresh bounded transactions. This slice adds no Netty handler or live
fan-out.

## Verification

Disposable PostgreSQL verifies first and duplicate results, stable IDs/sequence/
timestamp, conflicting reuse, outsider denial, one canonical row plus one V1
mapping, no duplicate sequence consumption, and denial after both memberships
end. The complete migration and gateway PostgreSQL gates remain required.
