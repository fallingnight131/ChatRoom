# ADR-0369: Real Two-Gateway Product Delivery Gate

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The earlier two-gateway proof composed adapters and embedded channels directly.
The product runtime gate then proved only one complete gateway through Redis
failure. Neither established that two independent production listeners sharing
PostgreSQL and Redis could exchange a user message without accidentally using a
process-local subscription.

Before testing gateway removal or rolling deployment, the normal healthy
cross-gateway path must prove every authority boundary: durable commit, outbox
relay, payload-free routing, local subscription matching, PostgreSQL
reauthorization, exact sequence repair, and client delivery.

## Decision

- Extend the disposable PostgreSQL/Redis product gate with two complete
  `GatewayRuntime` instances, each with independent product/admin ports,
  schedulers, boot IDs, route leases, consumers, relays, and connection state.
- Authenticate the sender only on gateway A. Authenticate the peer only on
  gateway B, complete authoritative empty history, flush that response, and
  activate B's external conversation route before submission.
- Submit one message through A. Require one stable acknowledgement and one
  PostgreSQL message/outbox row.
- Require the outbox to become published and B to emit the exact stable message
  ID, conversation sequence, sender, and body once. Assert no second peer event.
- Require B's fixed `chat_gateway_routing_hint_applied_total` counter to be
  non-zero. This is explicit evidence that B consumed and repaired a Redis hint,
  rather than receiving through A's local router.

## Consequences

The selected PostgreSQL outbox plus Redis route/Stream topology now works through
two real TLS/WSS product gateways in the healthy case. Redis carries no body or
authorization; gateway B obtains both authorization and message bytes from the
durable database before socket output. Duplicate relay/hint behavior remains
safe at the client-visible boundary.

The gate still has no load balancer and does not remove either gateway while a
session is active. It therefore does not prove readiness propagation, route
expiry/release on gateway loss, client reconnect placement, or rolling-upgrade
availability.

## Verification

`python3 tools/verify_m0.py --redis-outage` now runs this adjacent two-gateway
product test in the same disposable dependency topology as ADR-0368. The sender
on A received one acceptance at sequence 1; the peer on B received the matching
publication once; PostgreSQL contained one message and one published outbox row;
and B reported at least one applied routing hint. The full Backend test suite
continues to skip the real dependency path when endpoints are absent.

## Rollback

Remove the adjacent real-dependency test. Product behavior remains protected by
the unchanged default-off flag; leaving distributed routing disabled constructs
neither Redis graph and continues to use the single-gateway local router.
