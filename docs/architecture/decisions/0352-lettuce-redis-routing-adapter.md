# ADR-0352: Lettuce Redis Routing Adapter

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0351 defines expiring route and bounded target-stream ports. The first
adapter needs Redis Streams, scripts, ACL/TLS support, explicit timeouts, bounded
client buffering, reconnect behavior, and lifecycle ownership without adding a
framework to the modular backend.

## Decision

- Use the official Lettuce 7.6 client in a separate `routing-redis` module.
  Keep the application and gateway modules free of Lettuce types.
- Production configuration requires `rediss://` plus URI credentials. Plain
  `redis://` is accepted only for an explicit loopback capability-test mode.
  Configuration string rendering omits user information.
- Use one thread-safe non-blocking Lettuce connection for the first standalone
  Redis deployment, with PING activation, automatic reconnect, 100 ms through
  10 second command timeout, and a bounded 16 through 10,000 request queue.
- Store gateway leases as expiring strings, conversation targets as expiry-score
  sorted sets, and live hints as exact-length bounded Streams. Lua scripts make
  route publication conditional on a live gateway lease and remove stale route
  members during complete target lookup. Each lookup scans at most 1,024
  candidates; hitting that ceiling returns incomplete and retries after bounded
  lazy cleanup instead of doing unbounded Redis work or claiming completeness.
- Stream entries contain only event UUID, conversation UUID, and sequence. Exact
  `MAXLEN` is chosen for the initial correctness envelope; approximate trimming
  requires measured memory/latency evidence.
- Keep the module unreferenced by product runtime. TLS/ACL certificate and
  authentication-failure capability tests are required before composition.

## Consequences

The application retains infrastructure-neutral contracts and Redis remains
reconstructable. Client queues and stream memory are bounded, and stale gateway
routes disappear without a database migration. Exact stream trimming has a
higher per-append cost than approximate trimming and must be measured before a
capacity claim.

The first adapter is standalone-Redis only. Sentinel/Cluster topology, hash-tag
layout, consumer groups, acknowledgement, route renewal loops, and stream
consumer repair remain later slices.

## Verification

Unit tests enforce TLS/auth defaults and secret-redacting configuration output.
A real isolated local Redis test proves lease-before-route ordering, complete
target discovery, exact 100-entry trimming after 150 appends, lease expiry,
stale-route cleanup, adapter close, and reconnect. Full backend `check` includes
the module while the external capability test is opt-in through
`CHATROOM_TEST_REDIS_URI`.

## Rollback

Do not construct the module and remove its dependency/module registration if
needed. PostgreSQL history/outbox and process-local live delivery are unchanged.
