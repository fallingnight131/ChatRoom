# ADR-0365: Default-Off Distributed Runtime Composition

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The bounded component graph, post-response route activation, second repair, and
periodic route renewal now satisfy the correctness prerequisites for a runtime
preview. The product runtime still used only the local router and did not own or
observe Redis. Activation must preserve the existing default path, gate readiness
on all authoritative dependencies, drain clients before route teardown, and
expose fixed operational signals.

A real composition run also revealed a relay/local-publish race: Redis repair
could deliver a newly committed message before the originating request's local
publication. The latter then duplicated the socket event because it did not
check the per-channel observed sequence.

## Decision

- Parse the distributed routing configuration as part of the immutable gateway
  runtime configuration. The default remains disabled and constructs no Redis
  adapter or routing scheduler.
- When explicitly enabled, construct the complete factory graph and inject its
  distributed decorator into the product WebSocket server.
- Start admin observation first, then distributed lease/consumer/relay loops,
  then product admission. Global readiness requires both PostgreSQL health and
  a currently valid Redis gateway/conversation lease pass.
- On shutdown, withdraw readiness, stop admission, drain and close product
  connections, close distributed loops/release the boot lease/Redis, then close
  admin, workers, and PostgreSQL.
- Replace the premature `gateway_ready` startup log with `gateway_started`;
  `/health/ready` is the authoritative dynamic signal.
- Add fixed, identity-free admin metrics for Redis lease/hint consumption,
  outbox relay counters, and PostgreSQL outbox backlog. If the status read fails,
  emit only `chat_gateway_distributed_metrics_available 0` rather than failing
  the whole metrics endpoint.
- Suppress local message publication when that exact or later contiguous
  sequence was already delivered by Redis repair. Advance the observed high
  watermark only for the next contiguous sequence; never hide a sequence gap.
- Keep activation opt-in and do not claim production readiness until real Redis
  TLS/ACL failure gates and multi-gateway dependency-loss/rolling tests pass.

## Consequences

The Java product runtime can now exercise the selected M5 route end to end while
the ordinary configuration remains byte-for-byte single-gateway in behavior.
Redis loss makes the enabled instance unready but cannot erase PostgreSQL
messages. Shutdown does not remove routes while accepted product connections
are still draining.

This is an operational preview, not the M5 exit: real TLS/ACL, gateway loss,
load-balancer deregistration, rolling deployment, and multi-gateway reconnect
evidence remain required.

## Verification

Lifecycle tests prove start order, dynamic readiness, drain-before-routing-close,
and reverse ownership. Configuration tests prove the disabled default and
explicit enablement. Admin tests prove the fixed metrics surface. Router tests
prove Redis-first/local-second delivery is idempotent and noncontiguous delivery
does not advance the high watermark.

With disposable PostgreSQL and Redis, the product integration gate proves real
TLS WebSocket history response, route activation, message commit/outbox relay,
Redis hint consumption, exactly one peer event despite the publication race,
readiness, metrics, and clean shutdown. The separate two-gateway scenario also
continues to pass.

## Rollback

Leave `CHATROOM_GATEWAY_DISTRIBUTED_ROUTING_ENABLED` unset or false. No Redis
resource is constructed and the existing process-local router remains active.
