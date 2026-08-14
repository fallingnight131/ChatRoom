# ADR-0401: Portable Direct-Buffer Metrics

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

Heap metrics and process RSS answer different questions. Netty and TLS can use
direct byte buffers outside the Java heap, but RSS also includes thread stacks,
code cache, native libraries, allocator overhead, and shared pages. Without a
separate direct-buffer signal, an RSS increase cannot be compared with a major
portable off-heap category.

Java 21 exposes standard `BufferPoolMXBean` observations. The `direct` pool
reports buffer count, estimated memory used, and total capacity without a new
native dependency. These values still do not represent every native allocation.

## Decision

- Extend the fixed process-resource snapshot with direct-buffer metric
  availability, buffer count, memory used bytes, and total capacity bytes.
- Aggregate only platform buffer-pool beans named exactly `direct`. Mark the
  entire dimension unavailable and export zeros if no matching bean exists or
  any value is negative.
- Export fixed loopback metrics:
  - `chat_gateway_jvm_direct_buffer_metrics_available`;
  - `chat_gateway_jvm_direct_buffer_count`;
  - `chat_gateway_jvm_direct_buffer_memory_used_bytes`;
  - `chat_gateway_jvm_direct_buffer_total_capacity_bytes`.
- Keep readiness, admission, protocol, storage, and client behavior unchanged.
- Do not label direct-buffer memory as total off-heap, native memory, RSS, a
  configured limit, or a capacity threshold.

## Consequences

Operators can compare heap, direct buffers, and cached process RSS using
portable low-cardinality metrics. The signal can later be added to the shared
reconnect window without changing the process RSS provider boundary.

The MXBean value is an implementation estimate and omits other native memory.
Mapped buffers, class metadata, code cache, thread stacks, JNI allocations,
allocator fragmentation, and container limits remain separate dimensions.

## Verification

Unit tests cover fixed rendering and unavailable/value invariants. The gateway
admin integration path must expose availability as 0 or 1, require positive or
zero values consistently, and keep every value non-negative. The full Java
workspace check remains the regression gate.

## Rollback

Remove the four loopback metrics and the snapshot fields. Heap, GC, CPU, RSS,
queue, database, event-loop, protocol, data, and client behavior are unchanged.
