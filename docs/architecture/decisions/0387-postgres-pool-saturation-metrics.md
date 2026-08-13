# ADR-0387: PostgreSQL Pool Saturation Metrics

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The runnable Java gateway owns one bounded Hikari PostgreSQL connection pool.
Authentication, session resume, messaging, conversation, and device operations
share it. Existing readiness probes detect failure to obtain a valid connection,
and the performance harness can force connection timeouts, but the loopback
metrics surface cannot distinguish database-pool waiting from worker-pool or
network delay.

## Decision

- Export fixed-name gauges for Hikari active, idle, total, configured maximum,
  and threads awaiting a connection on the loopback-only metrics endpoint.
- Export a separate `postgres_pool_metrics_available` management-view gauge. If
  Hikari has not exposed its management view, report `available=0` with zero
  observations and retain the configured maximum instead of failing the metrics
  request.
- Validate every snapshot: counts are non-negative and connection counts cannot
  exceed the configured maximum. Do not require separately sampled Hikari
  gauges to reconcile exactly during concurrent transitions.
- Add no account, conversation, endpoint, SQL, or database-address labels.
- Do not change readiness, admission, connection timeouts, or pool sizing.

## Consequences

Operators and bounded benchmarks can observe connection pressure and waiters
without opening a second database connection. The metrics read Hikari's
in-process management view and therefore do not add database load.

Instantaneous, independently read Hikari gauges must be sampled during the
operation window and must not drive programmatic control decisions. They do not
provide query latency, transaction duration, SQL attribution, PostgreSQL server
capacity, or a production sizing rule.

## Verification

Unit tests require the exact fixed names, unavailable behavior, and rejection of
impossible snapshots. The real gateway admin endpoint must expose configured and
runtime values while its existing tests preserve health and other metrics.

## Rollback

Remove the snapshot supplier and renderer. No protocol, persistent schema,
readiness, connection-pool configuration, or product-listener behavior changes.
