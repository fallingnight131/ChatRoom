# ADR-0373: Abrupt Gateway Process Loss Gate

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0370 and ADR-0372 prove cooperative gateway replacement and HAProxy
readiness withdrawal. Both call the runtime's close path, so they revoke Redis
routes, stop new admission, and allow established WebSockets to drain. A crash,
host loss, out-of-memory kill, or forced orchestrator termination cannot perform
those actions. The remaining M5 risk is whether independently owned processes,
active health checks, expiring routes, durable storage, and client sync converge
without a shutdown hook.

## Decision

- Add a separate explicit `--gateway-crash` gate built on the real HAProxy
  harness. Keep it out of ordinary `--all` because it owns local services,
  Docker, and child JVM processes.
- Start each complete gateway as an independent `GatewayMain` JVM with the main
  runtime classpath. Redirect its existing redacted operational log to the
  disposable control directory and include that log when startup readiness
  fails.
- Place one authenticated, history-synchronized client on each gateway through
  HAProxy. Require two live Redis conversation routes and commit/deliver sequence
  1 before fault injection.
- Force-kill the sender's JVM without its shutdown hook. Require HAProxy to stop
  routing new sessions to that port, place the reconnect on the surviving
  gateway, and let the killed gateway's five-second route expire naturally.
- Require the reconnect to repair sequence 1 from PostgreSQL history, commit and
  deliver sequence 2, preserve conversation order, keep exactly two durable
  messages, and emit no duplicate peer event.

## Consequences

The scenario proves that loss of one independently running same-build gateway
does not lose a message already committed to PostgreSQL, does not leave its
Redis route permanently active, and does not prevent a client from reconnecting
through HAProxy and continuing on the survivor. No graceful close behavior is
used for the killed process.

The proof is intentionally bounded. It does not claim zero interruption for the
client attached to the killed process, preservation of an uncommitted in-flight
frame, multi-host or availability-zone tolerance, mixed-version safety, or a
safe reconnect-storm rate. Client backoff and fleet capacity still determine
the impact of correlated failures.

## Verification

Run:

```bash
python3 tools/verify_m0.py --gateway-crash
```

The gate removes child JVMs, HAProxy, PostgreSQL, Redis, certificates, logs, and
data directories on both success and failure.

## Rollback

Remove the explicit crash orchestrator and subprocess integration scenario.
The production gateway, protocol, persistent schema, HAProxy policy, and route
lease behavior are unchanged.
