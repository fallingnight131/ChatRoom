# ADR-0298: Compose only V1 room-settings reads in the detached gateway

- Status: Accepted
- Date: 2026-08-13

## Context

Windows and Web immediately request room settings after room entry. The
application and PostgreSQL boundaries now provide a complete authorized read,
but the V1 request type also multiplexes administrator mutation and cleanup.
The Java path does not yet own those mutation guarantees.

## Decision

Add a detached authenticated handler for the exact read shape:
`ROOM_SETTINGS_REQ.data` contains only one integral `roomId`. Bind the actor
from channel state, execute the PostgreSQL use case on the existing bounded
off-loop executor, and return the compatible `ROOM_SETTINGS_RSP` fields without
canonical identifiers. Stable invalid/access outcomes are typed and observable.

Any extra data field, including a limit, developer key, confirmation flag, or
future mutation option, is not a read. Malformed/mutation-shaped requests,
concurrent requests on one connection, saturation, dependency failure, and
encoding failure close generically without echoing private details. The handler
remains in `V1CompatibilityModule`; the product listener is unchanged.

## Consequences

- Existing room-entry reads can be validated end to end against custom
  PostgreSQL values.
- Java cannot silently acknowledge or discard legacy administration intent.
- Settings mutation remains a later vertical slice with its own authorization,
  cleanup, idempotency, and audit design.
