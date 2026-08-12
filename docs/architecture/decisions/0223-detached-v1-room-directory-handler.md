# ADR-0223: Add a Detached V1 Room Directory Handler

- Status: Accepted
- Date: 2026-08-13
- Owners: Java gateway and protocol compatibility
- Related milestone: M3

## Context

ADR-0222 created an authorized, transport-independent V1 room projection, but
the detached Java compatibility pipeline had no strict owner for
`ROOM_LIST_REQ`. Returning an empty list on mapping/database failure is unsafe:
both supported clients interpret a successful list as the authoritative access
set and prune local conversation state.

## Decision

- Add a strict duplicate-detecting JSON codec that owns only a bounded existing
  `ROOM_LIST_REQ` envelope and emits the established `ROOM_LIST_RSP` room fields.
- Run the application use case outside the Netty event loop through an injected
  executor. Use only the identity already bound to the channel; ignore all
  client identity fields.
- Permit one in-flight directory request per connection. Malformed owned input,
  concurrent duplicate work, executor saturation, mapping/database failure, or
  response-bound failure closes the connection with one generic reason and no
  empty/partial response.
- Suppress late success after replacement/closure, bound the response to 1 MiB,
  cap the legacy unread representation at its signed 32-bit client range, and
  expose only fixed-cardinality completion/failure/saturation telemetry.
- Keep the handler detached. Runtime pipeline composition and real PostgreSQL
  old-client verification remain a separate rollback boundary.

## Alternatives Considered

- Return `rooms: []` on failure: rejected because clients would treat a
  dependency error as revoked access and prune recoverable local caches.
- Perform the query on the event loop: rejected because directory/database
  latency would block unrelated connections.
- Queue repeated requests without a bound: rejected because one client could
  create unbounded database work and stale responses.

## Consequences

The exact post-login V1 room-list exchange can now be tested through Netty
without opening a listener. Friends, room avatars/members, history, reads, and
message writes remain on the C++ product path.

## Migration and Rollback

The slice is additive and uncomposed. Removing the codec, handler, telemetry
port, and tests restores ADR-0222 with no protocol or data migration.

## Verification

- `./gradlew :im-gateway:test --no-daemon`
- `python3 tools/verify_m0.py --java`
- Before composition: real PostgreSQL membership/mapping tests, bounded worker
  wiring, fixed telemetry implementation, and supported V1 consumer fixtures.
