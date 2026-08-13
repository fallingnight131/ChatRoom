# ADR-0362: Distributed Routing Component Factory

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The Redis adapter, PostgreSQL outbox relay, gateway lease, hint consumer, shared
local router, telemetry, and lifecycle owner had compatible boundaries but no
single composition point. Hand-assembling them in `GatewayRuntime` would spread
reviewed limits and make partial construction leaks likely. Disabled deployments
must not create Redis connections, background threads, or require otherwise
unused dependencies.

## Decision

- Add `DistributedGatewayRoutingFactory` as the only constructor for the full
  M5 message-routing component graph.
- Return no components when distributed routing is disabled, before validating
  or touching DataSource, router, Redis, scheduler, or random boot identity.
- When enabled, construct one Lettuce adapter shared behind route, publish, and
  consume ports; PostgreSQL remains the outbox and message-repair truth.
- Generate independent random gateway-boot and relay-owner UUIDs.
- Use reviewed fixed bounds: 30-second route lease, 10-second renewal, five-
  second outbox lease, batches of 100, at most 64 target gateways, a 1,000-entry
  stream, 100 ms healthy polling, 100 ms to 30 second failure backoff, and five-
  second shutdown.
- Own three named daemon scheduler threads and remove cancelled work.
- Return only the boot identity, registration boundary, lifecycle owner, and
  fixed-cardinality relay/consumer telemetry. Lease telemetry remains readable
  through the lifecycle owner.
- On any partial construction failure, stop the scheduler, close the Redis
  resource, and attach cleanup failures without replacing the original cause.
- Keep the factory uncalled by `GatewayRuntime` in this step.

## Consequences

The next activation slice can choose between no distributed resources and one
complete, consistently bounded component graph. Redis remains payload-free and
reconstructable; hint delivery still reauthorizes against PostgreSQL through
the same local router used by WebSocket subscriptions.

The graph is not started, registered with the history response path, included
in global readiness, or exposed through the admin endpoint yet. Product traffic
therefore remains process-local.

## Verification

Unit tests prove the disabled path performs no dependency access, enabled
composition shares one adapter and boot identity, normal close releases the
boot lease/scheduler/adapter, later construction failure cleans resources, and
cleanup failures are suppressed onto the original error. All distributed
routing tests and the complete backend check remain warning-clean.

## Rollback

Remove the uncalled factory and component handles. The existing default-off
configuration, independent components, and single-gateway product path remain.
