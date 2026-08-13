# ADR-0358: Two-Gateway PostgreSQL and Redis Proof

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The transactional message outbox, Redis route/stream adapter, relay pass, and
authorized local hint repair had only independent tests. Runtime composition
must not start until their shared boundary proves that one durable commit can
reach connections owned by two different gateway boot identities without
trusting Redis payloads or duplicating socket delivery.

## Decision

- Add a default-off integration scenario using disposable PostgreSQL and Redis.
- Register two distinct gateway boot leases and conversation routes, each with
  its own process-local router and authenticated connection.
- Commit one V2 message and its payload-free outbox row atomically, relay the
  event through the real Redis adapter, and consume each boot-specific stream.
- Require each gateway to reauthorize and load the exact message from
  PostgreSQL before local delivery.
- Republish the same hint to both streams and require duplicate classification
  with no second socket output.
- Run this scenario from the PostgreSQL verifier only when an explicit
  `CHATROOM_TEST_REDIS_URI` points to a disposable Redis instance. PostgreSQL-
  only verification remains independent of Redis.

## Consequences

The selected M5 components now have one repeatable cross-adapter vertical
proof. It demonstrates cross-gateway message routing and duplicate safety for
the current message-only event kind; Redis remains reconstructable and carries
no message body.

This does not activate distributed routing in `GatewayMain`, prove Redis
TLS/ACL behavior, exercise gateway loss or load-balancer deregistration, or add
outbox writers for other event kinds. Those remain activation gates.

## Verification

`TwoGatewayRedisPostgresIntegrationTest` verifies exact durable message/outbox
counts, two distinct Redis streams, PostgreSQL-backed delivery to both local
channels, cursor advancement, and duplicate suppression. Run it through:

```bash
CHATROOM_TEST_REDIS_URI=redis://127.0.0.1:<port> \
  python3 tools/verify_m0.py --postgres
```

The Redis endpoint must be isolated and disposable.

## Rollback

Remove the conditional integration scenario and its test-only dependencies.
No product configuration or traffic path changes.
