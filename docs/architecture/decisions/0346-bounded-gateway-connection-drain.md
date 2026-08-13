# ADR-0346: Bounded Gateway Connection Drain

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The Java V2 runtime already set `/health/ready` to 503 before closing the
product listener, but it immediately closed every child channel afterward. A
load balancer therefore had no bounded interval in which to observe readiness,
stop new routing, and preserve established sessions. A rolling deployment could
turn one gateway's entire connected population into an avoidable simultaneous
reconnect burst.

The schema-8 reconnect benchmark now provides local all-at-once, 100 ms, and 25
ms arrival curves. Both paced curves completed without errors, but no local
result establishes a safe production fleet reconnect rate. Web and Windows V2
already use capped exponential full jitter with a 500 ms initial ceiling and a
30 second cap.

Affected quality attributes are availability, reconnect behavior, deployment
safety, observability, and shutdown time. This decision changes process
lifecycle only; it does not change the V2 wire protocol, durable data, session
identity, or current single-gateway routing model.

## Decision

- Add `CHATROOM_GATEWAY_DRAIN_TIMEOUT_SECONDS`, defaulting to 15 seconds and
  bounded to `0..300`. Zero preserves an immediate-shutdown rollback path.
- On runtime close, publish readiness false first and keep the loopback admin
  server alive during drain.
- Close the product listener next so no new TCP/WebSocket connections are
  accepted. A drained server cannot restart its listener.
- Preserve established child channels until they close voluntarily or the
  configured monotonic timeout expires. Then force-close remaining children and
  continue reverse-order worker/database shutdown.
- Emit one fixed lifecycle outcome: `gateway_drain_complete`,
  `gateway_drain_timeout`, or a fixed exception-class category. Do not log
  session, device, account, peer, or conversation identity.
- Keep the existing Web/Windows V2 reconnect policy unchanged. A timed-out
  drain may close many sessions, but per-client full jitter distributes their
  resume attempts. The measured loopback curves are not a fleet rate limit.
- Configure the load balancer to stop routing on readiness failure and set its
  deregistration/termination grace longer than the application drain timeout.
  Roll only a bounded gateway subset at once.

## Consequences

The lifecycle now has an explicit admission barrier and a bounded period for
existing sessions and in-flight work. The admin endpoint remains available long
enough for an orchestrator to observe 503 readiness during that interval.

This slice does not migrate a live session to another gateway, broadcast a
server-draining protocol event, wait for an application-level in-flight command
counter, or prove multi-gateway delivery. Long-lived clients will normally stay
until the timeout and then reconnect. Those gaps remain part of the future
multi-gateway and chaos-test work.

## Verification

- configuration tests prove the 15 second default and reject values above the
  300 second bound;
- runtime tests prove readiness is false before listener admission stops, drain
  wait precedes forced product close, and reverse shutdown remains idempotent;
- a real local TLS test proves an established child survives admission stop, a
  short drain times out, voluntary socket close completes drain, and the stopped
  listener cannot restart;
- the full Java verification gate remains required before commit.

## Rollback

Set `CHATROOM_GATEWAY_DRAIN_TIMEOUT_SECONDS=0` for immediate operational
rollback. Code rollback removes the drain methods and configuration, restoring
readiness-clear followed by immediate product close. No database or protocol
migration is involved.

