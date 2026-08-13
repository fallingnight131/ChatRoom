# ADR-0389: Netty Event-Loop Lag Metrics

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

Authentication-worker and PostgreSQL-pool metrics cannot reveal a blocked Netty
worker event loop. The runnable gateway owns a bounded `NioEventLoopGroup`, but
its loopback operations surface currently has no event-loop delay or queued-task
signal.

## Decision

- Start one lightweight fixed-rate probe on each Netty worker event loop at a
  50 ms period when the product listener starts.
- For each worker, compare actual probe execution time with its fixed-rate
  expected time. Export the maximum latest observation across workers, maximum
  observed lag since listener start, aggregate probe sample count, worker count,
  and aggregate pending-task count.
- Expose an explicit metrics-availability gauge. Before product start and after
  product close, return an unavailable zero snapshot rather than inspecting a
  missing group.
- Cancel probes before shutting down the worker group. The product listener owns
  both lifecycles.
- Add no connection, account, endpoint, or event-loop-thread labels. Do not use
  these observational metrics to change readiness or admission.

## Consequences

Operators can distinguish worker/database pressure from observable Netty loop
delay and backlog without an external profiler. Four default workers add four
small scheduled tasks every 50 ms.

The latest observations can miss shorter stalls between probes. The since-start
maximum can retain startup or one-off host noise, and pending-task counts are
instantaneous. These metrics are not a CPU profile, per-handler attribution, or
production capacity rule.

## Verification

Unit tests require exact fixed metric names, seconds conversion, unavailable
behavior, and snapshot invariants. A real TLS listener test waits for at least
one probe per configured worker and verifies the metrics become unavailable
after close. Full gateway checks preserve listener and shutdown behavior.

## Rollback

Remove the monitor, supplier, and renderer. No product protocol, persistent
schema, readiness, admission, or event-loop worker configuration changes.
