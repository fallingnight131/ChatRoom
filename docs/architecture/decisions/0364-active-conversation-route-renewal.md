# ADR-0364: Active Conversation Route Renewal

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

Conversation routes intentionally expire after 30 seconds. Initial post-history
publication alone would make a healthy long-lived subscription disappear from
Redis, after which new outbox events could resolve no target and be marked
published. Gateway boot-lease renewal did not include active conversation routes.

## Decision

- Add an application operation that republishes one conversation route with a
  fresh expiry and a nonnegative observed sequence, without repeating the
  activation repair.
- Let the local router expose an immutable snapshot of active conversation IDs
  and their maximum per-channel observed sequence.
- Make the distributed router renew every active snapshot entry, continue after
  individual dependency failures, and return false if any route was rejected or
  unavailable. An empty snapshot succeeds without Redis conversation writes.
- Construct the existing lease loop with one composite renewal operation:
  renew the gateway boot lease first, then all active conversation routes.
- Treat either gateway or conversation renewal failure as lease-loop failure so
  the future distributed readiness gate fails closed before expiry.
- Retain the existing 10-second healthy interval for the 30-second route lease.
  New subscriptions still publish immediately after history response; periodic
  renewal is not their activation path.

## Consequences

Long-lived local subscriptions remain discoverable while Redis is healthy.
Disconnect races may briefly recreate a route from an earlier immutable
snapshot, but the route is payload-free, consumers recheck local subscription,
and it expires within the bounded lease. PostgreSQL remains durable truth.

The component graph is still not connected to `GatewayRuntime`; activation and
global readiness/shutdown tests remain the next slice.

## Verification

Application tests prove renewed route identity, sequence, and expiry. Router
tests prove active routes renew, failures aggregate, observed sequence is
carried, and unsubscribed conversations stop renewing. Factory and lease-loop
tests prove the composed path and bounded lifecycle remain valid.

## Rollback

Remove periodic conversation renewal and keep distributed routing disabled.
Initial route activation alone is insufficient for production use.
