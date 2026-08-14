# ADR-0398: In-Window GC Collection Evidence

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0397 exposes cumulative JVM garbage-collection count and collection elapsed
time. Reading only an idle snapshot cannot tell whether collection activity
changed during a reconnect window. The shared five-millisecond sampler already
captures CPU, heap, queue, database, and event-loop observations from the same
independent gateway child JVM.

Collection elapsed time is not exact stop-the-world pause time. Evidence must
retain that distinction while reconciling cumulative counters.

## Decision

- Upgrade new raw dual-edge evidence to schema version 8 while preserving raw
  schemas 1 through 7.
- Require GC management metrics to be available in every shared sample.
- Record cumulative collection count and collection-time milliseconds before
  and after the reconnect window plus exact non-negative deltas.
- Add a dedicated `gcCollectionActivity` block rather than silently extending
  the existing process-resource block.
- Upgrade new repeated aggregates to schema version 3 when all nine children
  use schema version 8. Preserve aggregate schema 1/child 6 and aggregate schema
  2/child 7 as historical pairs; reject mixed or mismatched schemas.
- Include per-run collection count/time deltas in the aggregate summaries.
  Do not use GC collection activity as a capacity or sustained-pressure
  threshold in this slice.

## Consequences

The ladder can now show whether standard-JDK GC collection counters changed in
the same window as reconnect latency and resource pressure. Historical clean
aggregate evidence remains independently reproducible under its original
schema pair.

A zero delta means the management counters did not advance at their resolution;
it does not prove absence of every application pause. Positive collection time
may include concurrent work. Exact pause distribution, allocation rate, RSS,
native/direct-buffer pressure, container quotas, and production capacity remain
unmeasured.

## Verification

Contract tests preserve raw schemas 1 through 8 and aggregate schemas 1 through
3, reject unavailable metrics, counter regression, bad deltas, sample mismatch,
extra fields, and aggregate/child schema mismatch. A real `step-12` run must
produce strictly valid schema-8 evidence.

## Rollback

Return new raw evidence to schema 7 and aggregate evidence to schema 2. Keep the
runtime GC metrics for operations. No product protocol, data, readiness,
admission, client, or deployment behavior changes.
