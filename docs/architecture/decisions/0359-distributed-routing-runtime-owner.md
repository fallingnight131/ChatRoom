# ADR-0359: Distributed Routing Runtime Owner

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The route-lease, live-hint consumer, and outbox-relay loops each own scheduled
work and share one Redis adapter. Constructing or closing them independently in
the future product composition could expose traffic before a lease is attempted,
leave scheduled work using a closed connection, or leak a boot lease after
partial startup failure.

## Decision

- Add one default-off `DistributedGatewayRoutingRuntime` that receives ownership
  of the three loops, their scheduler, the route registration service, and the
  shared routing adapter.
- Start the lease loop before the hint consumer and relay loops.
- Report the distributed slice ready only after start and while the gateway boot
  lease is valid.
- Treat partial startup failure as terminal and run the same complete cleanup as
  normal shutdown.
- Close relay, consumer, and lease loops; stop and await the scheduler for a
  reviewed 100 ms through 30 second bound; release the boot lease; then close
  the Redis adapter.
- Continue later cleanup after an earlier failure and preserve all failures by
  suppression. Repeated close is idempotent and repeated start is rejected.

## Consequences

Future product composition has one resource boundary and one deterministic
rollback path instead of scattered background-task ownership. Route release
cannot race newly scheduled renewal work after a successful scheduler stop.

The owner is still unconstructed by `GatewayMain`. It does not create the
component graph, register conversation routes, alter global readiness, or
activate Redis traffic.

## Verification

Unit tests prove start order, lease-gated readiness, deterministic close order,
idempotent close, partial-start rollback, bounded scheduler-timeout reporting,
and continued adapter/lease cleanup after timeout.

## Rollback

Remove the uncomposed owner and its tests. Existing independent loops and the
single-gateway product path remain unchanged.
