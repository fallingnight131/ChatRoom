# ADR-0392: In-Window Process Resource Evidence

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0391 exposes portable cumulative CPU time and JVM heap state. The shared
dual-edge sampler can read these values alongside worker, database, and
event-loop pressure, allowing resource deltas to cover the same recovery window.

## Decision

- Upgrade new dual-edge evidence to schema version 5 while preserving schema
  versions 1 through 4 as historical contracts.
- Require CPU time availability for every sample. Record cumulative CPU
  microseconds before and after the window plus their exact delta.
- Record heap used bytes before, after, and maximum observed. Record committed
  bytes before/after, fixed effective heap maximum, uptime before/after/delta,
  and available processors.
- Allow committed heap to grow during the window; validate used/committed/max
  bounds at the corresponding endpoints rather than pretending heap commitment
  is immutable.
- Keep the five-millisecond shared snapshot target cadence. Do not derive CPU
  percentage in the producer; evidence consumers have both CPU-time and uptime
  deltas plus processor count.

## Consequences

The bounded curve can now report portable CPU time consumed and heap evolution
over the recovery window. Management reads add small allocation/management API
overhead and remain part of the benchmark identity.

This does not measure RSS, native/off-heap memory, GC pauses, container quotas,
host CPU contention, or production capacity. Short CPU deltas can be affected by
platform timer resolution.

## Verification

Contract tests preserve schemas 1 through 5 and reject unavailable CPU time,
non-monotonic counters, bad deltas, impossible heap bounds, processor mismatch,
or schema extensions without a version upgrade. The real harness must produce
clean exact-revision schema version 5 evidence before a baseline is committed.

## Rollback

Return new evidence to schema version 4 and omit process resources. Runtime
metrics from ADR-0391 remain. No product protocol, data model, readiness,
admission, or JVM configuration changes.
