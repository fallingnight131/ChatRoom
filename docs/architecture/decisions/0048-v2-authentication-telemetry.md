# ADR-0048: V2 Authentication Telemetry

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The V2 authentication boundary emits accepted, rejected, failed, worker
saturation, credential-upgrade-pending, and admission-denial events. A no-op or
test sink is insufficient for operators to distinguish credential rejection,
dependency failure, overload, and abuse controls. Telemetry must remain bounded
and must never turn account, peer, request, exception, password, or token data
into labels or logs.

## Decision

- Add a thread-safe gateway telemetry sink using `LongAdder` counters, fixed
  enum dimensions, and an immutable snapshot. Dynamic user/peer/request values
  are not accepted by its API.
- Count accepted, rejected, internal failure, worker saturation, legacy
  credential upgrade pending, and admission denial by the fixed limiter
  dimension.
- Measure only authentication use-case execution with monotonic `nanoTime`;
  queue wait and network time remain separate concerns. Export count, total,
  maximum, and fixed non-cumulative duration distribution buckets from 1 ms to
  5 s plus overflow. No unit test result is a capacity claim.
- Sample saturation and admission-denial warning logs at the first and every
  power-of-two cumulative occurrence per fixed dimension. Log only
  `event`, `dimension`, and `count` key/value fields.
- Treat telemetry failures as non-authoritative: a sink exception never changes
  an authentication result. Admission-control failures still fail closed because
  they are security decisions rather than diagnostics.
- Keep the snapshot independent of a metrics vendor. Listener/deployment
  bootstrap must attach it to the chosen registry/scrape endpoint and configure
  retention/alerts before traffic enablement.

## Consequences

- Operators can distinguish invalid credentials, internal failures, executor
  pressure, credential migration debt, and each admission dimension without
  high-cardinality or identifying data.
- The System Logger provides immediately consumable structured warnings, while
  the immutable snapshot supports later Micrometer/OpenTelemetry/Prometheus
  adaptation without coupling transport logic to that vendor.
- This is not yet a deployed dashboard, alert, or scrape endpoint.

## Verification

Tests verify counters, fixed duration buckets, total/maximum duration, upgrade
pending, fixed dimension counts, and exact first/2/4 sampled log messages. They
also assert representative account and peer strings never appear in logs. The
full Java gate passes.

## Rollback

Inject the no-op sink into the inactive V2 handler. Do not enable traffic without
equivalent non-secret saturation, failure, denial, and latency visibility.
