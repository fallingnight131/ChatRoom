# ADR-0385: Authentication Worker Saturation Metrics

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

Authentication and durable session resume run on a bounded gateway-owned worker
pool. The pool exposes active and queued counts internally, but the loopback
Prometheus surface exports only authentication outcomes and duration. During an
edge-failure reconnect concentration, operators therefore cannot distinguish
normal TLS/protocol latency from authentication-worker saturation.

The messaging worker pool already exports equivalent fixed-name gauges. Adding
unbounded account, endpoint, or client labels would create cardinality and
privacy risk and is unnecessary for saturation diagnosis.

## Decision

- Export `chat_gateway_authentication_workers_active` and
  `chat_gateway_authentication_queue_size` as fixed-name gauges on the existing
  loopback-only `/metrics` endpoint.
- Wire the gauges directly to the one `AuthenticationWorkerPool` owned by the
  runnable gateway composition root.
- Reject negative renderer inputs and add no identity, address, endpoint, or
  error labels.
- Keep readiness behavior unchanged. These metrics diagnose load; they do not
  automatically withdraw a gateway or change admission limits.

## Consequences

Reconnect and login measurements can now capture whether the bounded
authentication executor was busy or queued. The gauges complement the existing
accepted/rejected/failed/saturated counters and execution-duration histogram.

They do not expose Hikari pool utilization, Netty event-loop lag, CPU, RSS, or
database wait time. A zero value sampled before and after a curve does not prove
there was no transient peak; the benchmark must sample during the recovery
window before making that narrower claim.

## Verification

Unit tests require exact fixed metric names and values on both the renderer and
real loopback admin endpoint. Full gateway tests must retain the existing
health, metrics, and distributed-routing surfaces.

## Rollback

Remove the two suppliers and gauges. No protocol, persistent schema, readiness,
or product-listener behavior changes.
