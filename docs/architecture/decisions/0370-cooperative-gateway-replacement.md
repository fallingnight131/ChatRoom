# ADR-0370: Cooperative Gateway Replacement Rehearsal

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

Two healthy product gateways can now exchange a message, but that does not show
how a connected client recovers while one gateway is replaced. A rolling
topology must preserve durable sequence truth, keep unaffected gateways serving,
remove the old gateway's ephemeral lease, let a client reconnect elsewhere, and
avoid duplicate delivery before and after history repair.

The repository does not yet contain a real load balancer or two different
released gateway binaries. The smallest honest slice is therefore a cooperative
same-build rehearsal with explicit client placement.

## Decision

- Start gateways A and B against the same disposable PostgreSQL and Redis. Keep
  the sender only on A and the caught-up receiver only on B.
- Submit sequence 1 through A and require one cross-gateway event on B.
- Model a cooperative drain: the sender completes a normal WebSocket close,
  then A withdraws readiness, stops admission, drains, releases its Redis boot
  lease, and closes. Do not treat a forced drain timeout as covered by this gate.
- Require B to remain ready and retain its original authenticated WSS receiver
  throughout A's removal.
- Start replacement gateway C with a new boot identity. Reconnect the sender
  using the same account and client device ID, read authoritative history, and
  require sequence 1 before reactivating the route.
- Submit a new stable client message through C. Require sequence 2 to appear
  locally on C and remotely on the unchanged B connection exactly once. Require
  two durable messages and two published outbox rows in total.
- Permit the disposable gate to select `outage`, `cross-gateway`, or `rolling`
  scenarios independently for diagnosis; the repository-level gate runs all.

## Consequences

The selected topology now has end-to-end evidence for cooperative same-build
gateway replacement with one unaffected gateway continuously serving. The
client recovery path relies on server-authoritative PostgreSQL history rather
than Redis Stream position, and stable client/device identity survives placement
on a new boot gateway.

This is not the M5 rolling-deployment exit. There is no external load balancer,
no observed readiness-propagation delay, no mixed old/new binary, no abrupt
gateway process death, and no proof for clients that refuse to close before the
drain deadline. Those remain explicit gates rather than inferred guarantees.

## Verification

`python3 tools/verify_redis_outage.py --scenario rolling` passed with A, B, and
replacement C as complete TLS/WSS `GatewayRuntime` instances. B stayed ready;
the sender reauthenticated on C with the same device ID; C history returned the
one prior message; sequence 2 reached both C's local subscription and B's
unchanged remote subscription once; and PostgreSQL converged on exactly two
messages and two published outbox rows.

The aggregate `python3 tools/verify_m0.py --redis-outage` gate runs this rehearsal
alongside the Redis dependency-loss and healthy two-gateway scenarios.

## Rollback

Remove the rolling scenario and selector without changing product behavior.
Operational rollback remains leaving distributed routing disabled, which
constructs no Redis graph and uses the single-gateway local router.
