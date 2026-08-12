# ADR-0262: Define V1 Room Text/Emoji Submission

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define a transport-independent V1 room text/emoji submission boundary. Bind
sender account and device from authenticated state and accept only a positive
signed-32-bit V1 room ID, a 1–128-byte `clientMessageId`, a non-empty body up to
65,536 UTF-8 bytes, and exact `text` or `emoji` presentation. Client sender,
timestamp, message ID, sequence, role, and membership claims are never authority.

Future PostgreSQL persistence must resolve the V1 `ROOM` mapping to an active
GROUP membership and non-revoked device, then atomically create one canonical
UTF-8 type-1 message and one positive V1 ROOM message mapping. Exact retry
returns the same result with `duplicate=true`; conflicting reuse consumes no
sequence. Canonical conversation identity is internal routing context and must
never cross V1. Only first acceptance may emit `CHAT_MSG`. Attachments are
excluded because their target path uses object storage.

This slice adds no schema, adapter, handler, or route. Rollback removes the
application types without affecting persisted state.

## Verification

Application tests prove authenticated identity propagation, input bounds, and
target consistency. Atomic authorization and retry need PostgreSQL tests next.
