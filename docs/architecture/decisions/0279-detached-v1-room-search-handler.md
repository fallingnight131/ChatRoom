# ADR-0279: Compose Detached V1 Room Search Handling

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0278

## Decision

Compose strict bounded V1 `ROOM_SEARCH_REQ` handling in the detached Java
compatibility module. Bind the actor to authenticated channel state, accept
only the exact `data.keyword` shape, and execute at most one search per channel
off the event loop. Encode compatible `ROOM_SEARCH_RSP` success fields
`roomId`, `roomName`, `creatorId`, and `memberCount`; canonical identities never
enter the transport result.

Policy-rejected input returns the existing localized unsuccessful response and
keeps the connection usable. Malformed, concurrent, saturated, dependency-
failed, or stale work fails closed. Fixed telemetry contains only outcome,
result count, duration, failure, and saturation, never keyword or identity.

The detached handler does not activate the product listener. Rollback removes
it from the compatibility pipeline; the read-only PostgreSQL projection has no
durable side effects.

## Verification

Codec/handler tests prove strict field shape, actor binding, UUID-free response,
business-rejection continuity, downstream pass-through, malformed closure,
dependency failure, and saturation closure. Disposable PostgreSQL proves an
imported login can search a mapped room, observe creator and active-member
fields, exclude a nonmatching native-owned room, and receive no UUID.
