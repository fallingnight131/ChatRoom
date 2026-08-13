# ADR-0367: Bounded Distributed Route-Lease Policy

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The distributed gateway factory fixed gateway and conversation routes at a
30-second lease renewed every 10 seconds. Those defaults are conservative, but
a real Redis outage gate would need to wait the full lease before readiness
could correctly become false. Hard-coding a shorter test-only constant would no
longer exercise the same product composition, while accepting unrestricted
timing could create route flapping or renew after expiry.

Route expiry is an availability and correctness policy: a stale gateway must
disappear, but a transient dependency delay must not eject a healthy gateway.

## Decision

- Add `CHATROOM_REDIS_ROUTE_LEASE_SECONDS` to the enabled distributed-routing
  configuration. Keep 30 seconds as the default and accept only integer values
  from 5 through 60 seconds.
- Derive the healthy renewal interval from the selected lease rather than
  exposing a second independent setting. Use one third of the lease, bounded to
  one through ten seconds; this is always no greater than half the lease.
- Cap the lease loop's maximum failure retry delay at the smaller of five
  seconds and half the selected lease. Preserve the existing 100-millisecond
  initial retry.
- Keep the configuration ignored while distributed routing is disabled. Include
  the non-secret effective lease and renewal durations in redacted diagnostic
  configuration text.

## Consequences

The ordinary product behavior remains a 30-second lease renewed every 10
seconds. Disposable failure verification can select five seconds and observe
fail-closed readiness in bounded time through the same runtime graph. Operators
may tune within the reviewed range, but must coordinate the value with Redis
latency, health-check propagation, gateway drain, and reconnect backoff.

The setting does not make readiness instantaneous: the last confirmed lease
remains valid until its authoritative local expiry. Redis data remains
reconstructable and PostgreSQL remains durable truth.

## Verification

Configuration tests prove the unchanged 30/10-second default, the five/one-
second lower bound, and rejection of values below five, above sixty, and
non-integers. Factory tests prove the derived values satisfy the existing lease
loop timing bounds and preserve resource ownership.

The next real dependency gate must stop Redis while the product runtime remains
alive, observe readiness withdrawal no later than the selected lease expiry,
restart the same endpoint, and prove readiness and durable message delivery
recover.

## Rollback

Remove the environment setting and return the factory to its 30-second lease
and 10-second renewal constants. Leaving the setting absent already produces
that behavior, so operational rollback requires no data or protocol migration.
