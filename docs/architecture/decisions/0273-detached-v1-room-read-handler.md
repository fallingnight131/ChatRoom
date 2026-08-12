# ADR-0273: Compose Detached V1 Room Read Handling

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0272

## Decision

Compose strict bounded V1 `MARK_ROOM_READ` handling in the detached Java
compatibility module. Bind the actor to authenticated channel state, accept only
a positive mapped room ID, and execute at most one cursor mutation per channel
off the event loop. Preserve the V1 response-free shape for successful,
unchanged, access-denied, and invalid-ID business results.

Malformed, concurrent, saturated, dependency-failed, or stale work fails
closed. Fixed telemetry records only outcome, sequence delta, duration,
failure, and saturation; it contains no account or room identity. No room read
receipt or notification is introduced.

This detached handler does not activate the product listener. Rollback removes
it from the detached pipeline; the monotonic persisted cursor remains valid.

## Verification

Handler tests prove authenticated actor binding, response-free advancement and
business denial, downstream pass-through, malformed closure, and saturation
closure. Disposable PostgreSQL proves login, room send and recall, mark-read
without an outbound frame, then a fresh `ROOM_LIST_RSP` with zero unread for the
same room. Persistence verification proves exact-account isolation.
