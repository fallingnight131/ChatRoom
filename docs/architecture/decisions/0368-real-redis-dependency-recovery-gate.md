# ADR-0368: Real Redis Dependency-Loss and Recovery Gate

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The distributed runtime marks a gateway ready only while its Redis boot and
conversation routes have a valid local lease. Unit tests covered expiry and the
adapter had basic reconnect evidence, but neither proved the composed product
behavior when the Redis process dies under authenticated client traffic.

Redis is reconstructable routing state, whereas PostgreSQL messages and outbox
rows are durable truth. A valid failure gate therefore must withdraw new-traffic
readiness after the last confirmed lease, keep process liveness, preserve
existing client connections, accept a durable message without Redis, and
converge after an empty Redis restart without duplicate user-visible delivery.

## Decision

- Add `tools/verify_redis_outage.py` and expose it as the explicit
  `python3 tools/verify_m0.py --redis-outage` gate. Keep it out of `--all`
  because it owns local PostgreSQL and Redis processes.
- Create disposable PostgreSQL and non-persistent Redis instances on random
  loopback ports. Invoke the dedicated Gradle integration test with a private
  filesystem control directory; consume one stop request and one start request
  exactly once.
- Exercise the production `GatewayRuntime`, admin endpoints, and TLS/WSS
  listener with distributed routing enabled and the reviewed five-second lease.
  Keep two authenticated sessions connected and activate the peer's
  conversation route before failure.
- Stop Redis while leaving the gateway and PostgreSQL alive. Require readiness
  503 no later than lease expiry, liveness 200, and a non-zero fixed lease-
  failure counter.
- Submit one message through the existing WSS session during the outage. Require
  one acknowledgement, one local peer publication, one durable PostgreSQL
  message, and one unpublished transactional outbox row.
- Restart Redis empty at the same endpoint. Require the existing Lettuce adapter
  to reconnect, active routes and the boot lease to rebuild, readiness to return
  200, and the durable outbox row to become published without a second peer
  event.

## Consequences

An enabled gateway now has repeatable evidence for fail-closed Redis readiness
and eventual recovery without losing a message committed during the outage.
Existing same-gateway sessions remain usable because Redis does not authorize or
store message truth. A load balancer can use readiness to stop new traffic after
the bounded lease window.

The drill deliberately uses explicit plaintext loopback Redis to isolate process
failure semantics; ADR-0366 independently proves TLS, certificate, credentials,
and scoped ACL behavior against the same adapter. Combining the two does not
replace production certificate/secret operations. This gate has only one
gateway, so it does not prove load-balancer propagation, peer-gateway survival,
reconnect placement, or rolling upgrades.

## Verification

The gate passed with one Redis stop and one empty restart. The live product
reported ready 200, then ready 503/live 200 after the five-second lease expired,
then ready 200 after recovery. The outage submission produced one durable
message and one pending outbox row; after recovery that row was published and
the already connected peer observed no duplicate event. Metrics reported a
valid recovered lease and at least one failed renewal.

The ordinary Backend `check` task compiles this integration path and skips it
without all three disposable endpoint/control variables.

## Rollback

Remove the explicit gate and its test control environment. Product rollback is
unchanged: leave `CHATROOM_GATEWAY_DISTRIBUTED_ROUTING_ENABLED` false to use the
process-local router and construct no Redis dependency.
