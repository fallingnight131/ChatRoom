# ADR-0292: Compose Detached V1 Room Leaving

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Follows: ADR-0291

## Decision

Compose strict bounded `LEAVE_ROOM` handling in the detached Java V1
compatibility module. Bind the actor only from authenticated channel state and
accept one integral `roomId`; unknown data fields, duplicate fields, fractional
numbers, concurrent commands, saturation, and dependency failures close with a
generic reason. Stable business rejection returns `LEAVE_ROOM_RSP` and keeps the
connection usable. Canonical UUIDs never cross the V1 wire.

Execute persistence on the bounded directory executor. Snapshot current local
connections before dispatch, then, only after a committed `newLeave:true`
non-dissolving result, filter that snapshot through authoritative active
PostgreSQL membership. Send compatible `USER_LEFT` to remaining local members.
If ownership transferred, send `ADMIN_STATUS {isAdmin:true}` to the successor
and a compatible system message to remaining local members. Duplicate,
dissolved, and rejected results emit no room notification.

The durable `LEAVE_ROOM_RSP` is written even if post-commit audience projection,
encoding, or local routing fails; telemetry records that secondary failure.
Fixed outcomes distinguish routed/no-local/dissolved/duplicate and business
rejection without identifiers. This is process-local routing only; M5 owns
multi-gateway fan-out.

The product listener remains unchanged. Rollback removes the handler from the
detached pipeline; already committed memberships and lifecycle state remain
valid.

## Verification

Codec/handler tests prove strict fields, authenticated actor binding,
UUID-free resource projection, first-only `USER_LEFT`/`ADMIN_STATUS`/system
fan-out, duplicate/dissolution/rejection suppression, committed-success survival
after audience failure, and malformed/dependency/saturation closure. Disposable
PostgreSQL proves login, protected-room membership recovery, first leave, one
owner notification, exact retry suppression, replacement-login directory
exclusion, and one remaining active member.
