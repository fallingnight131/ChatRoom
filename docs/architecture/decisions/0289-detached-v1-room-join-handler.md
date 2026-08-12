# ADR-0289: Compose Detached V1 Room Joining

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Follows: ADR-0288

## Decision

Compose strict bounded `JOIN_ROOM_REQ` handling in the detached Java V1
compatibility module. Bind the actor solely from authenticated channel state and
accept only an integral `roomId` plus optional password. The codec copies
password text into owned UTF-8 bytes and every codec, handler, and application
copy is erased deterministically.

When a password is supplied, consume the existing process-wide, direct-peer,
and normalized `room:<id>` authentication admission budget before dispatching
any Argon2 work. Admission denial keeps the connection usable and returns a
compatible password challenge with stable `RATE_LIMITED` plus bounded
`retryAfterMs`. A verified successful join clears only that room limiter key.
This control remains process-local; Redis coordination belongs to M5.

Run at most one join per connection on the bounded directory executor. Preserve
V1 `JOIN_ROOM_RSP` fields `success`, `roomId`, `roomName`, `isAdmin`,
`newJoin`, and `needPassword`; additive `errorCode` is ignorable by old clients.
Malformed, concurrent, saturated, dependency-failed, or encoding-failed work
closes generically. Stable business rejection and admission denial keep the
connection open.

After a committed `newJoin:true`, filter the current process-local connection
snapshot through authoritative PostgreSQL membership and send `USER_JOINED` to
other mapped active members. Duplicate joins and all rejection paths never
notify. Audience or notification failure is observable but cannot reinterpret
an already committed membership as failed; the joining client still receives
the durable success. Multi-gateway routing is not claimed.

The product listener remains unchanged. Rollback removes the handler from the
detached module; durable memberships remain valid and directory-visible.

## Verification

Codec/handler tests prove strict fields, password cleanup, authenticated actor
binding, pre-hash admission denial without use-case execution, compatible
responses without canonical account/conversation UUID leakage, first-only
filtered fan-out, duplicate suppression,
business continuity, and malformed/dependency/saturation closure. Disposable
PostgreSQL proves protected missing/wrong/correct password attempts, one durable
membership, one owner notification, idempotent repeat without notification,
and replacement-login recovery through `ROOM_LIST_RSP`.
