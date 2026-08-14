# ADR-0397: Portable JVM GC Collection Metrics

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The duration-aware reconnect ladder records heap used, committed, and maximum
values but cannot tell whether garbage collection occurred during the window.
Java's standard management API exposes cumulative collection count and
collection elapsed time for each active garbage collector without a native
agent or platform-specific filesystem.

`GarbageCollectorMXBean.getCollectionTime()` is implementation-dependent total
collection elapsed time. For concurrent collectors it must not be relabeled as
application stop-the-world pause time. The standard API also does not expose
portable process RSS.

## Decision

- Sum non-negative collection counts and collection-time milliseconds across
  all `GarbageCollectorMXBean` instances for each process-resource snapshot.
- Export fixed-name, identity-free loopback metrics:
  - `chat_gateway_jvm_gc_metrics_available`;
  - `chat_gateway_jvm_gc_collections_total`;
  - `chat_gateway_jvm_gc_collection_seconds_total`.
- If any collector reports an undefined negative value, mark GC metrics
  unavailable and export zero counters. Do not mix a partial collector set.
- Extend the immutable process-resource snapshot and its invariants. Unavailable
  GC metrics must have zero count and time.
- Keep these values diagnostic only. They do not affect liveness, readiness,
  admission, worker sizing, heap configuration, or collector selection.
- Do not call collection time "pause time". Exact stop-the-world pause evidence
  requires GC notification/JFR or reviewed runtime instrumentation in a later
  change.

## Consequences

Operations and the reconnect sampler can now observe whether cumulative JVM
collection activity changed during a bounded window using only JDK APIs. The
metrics have fixed cardinality and no collector-name labels.

Collection time may include concurrent work, is millisecond-granularity, and is
not a latency attribution mechanism. RSS, native/direct-buffer use, exact GC
pause distribution, allocation rate, container limits, and production capacity
remain unmeasured.

## Verification

Unit tests verify Prometheus names/units and snapshot invariants, including the
fail-closed unavailable state. The admin endpoint test verifies composition into
the existing loopback metrics response. A later evidence schema must reconcile
before/after/delta values in the shared reconnect window.

## Rollback

Remove the three GC metrics and snapshot fields. Existing CPU, heap, uptime,
processor, database, event-loop and authentication metrics remain unchanged.
No protocol, persistent data, product client, or deployment migration is needed.
