# ADR-0402: In-Window Direct-Buffer Evidence

- Status: Accepted
- Date: 2026-08-16
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0401 exposes standard-JDK direct-buffer count, estimated used bytes, and
total capacity on the loopback endpoint. An idle metric snapshot cannot show
whether this major Netty/TLS off-heap category moves during the bounded
dual-edge reconnect window. Raw schema 9 already samples CPU, heap, GC, RSS,
queues, PostgreSQL, and the event loop through one five-millisecond observation
loop.

Direct-buffer MXBean values are portable observations but are not total native
memory, RSS, allocation rate, or configured limits. The evidence format must
retain availability and avoid turning these values into a capacity threshold.

## Decision

- Upgrade new raw dual-edge reconnect evidence to schema version 10 while
  preserving raw schemas 1 through 9.
- Add a dedicated `directBufferActivity` block containing the shared sample
  interval/count, unavailable sample count, and before/after/maximum values for
  direct-buffer count, estimated memory used bytes, and total capacity bytes.
- Require every maximum to cover its endpoints. A fully unavailable window
  must keep all direct-buffer values at zero. An available window may still
  report zero because a process can legitimately own no direct buffers.
- Upgrade repeated aggregates to schema version 5 when all nine children use
  raw schema version 10. Preserve earlier aggregate/raw pairs and reject mixed
  children.
- Include per-run availability and all endpoint/maximum values in aggregate
  summaries. Do not add a direct-buffer pressure rule in this slice.

## Consequences

Reconnect evidence can compare heap, direct buffers, and RSS over the same
window without conflating their semantics. Historical evidence remains valid
under its original schema pair.

The five-millisecond HTTP observation loop does not measure allocation rate or
short-lived buffers between scrapes. Mapped buffers, allocator fragmentation,
thread stacks, class metadata, code cache, JNI allocations, container limits,
and total native memory remain unmeasured.

## Verification

Contract tests must preserve raw schemas 1 through 10 and aggregate schemas 1
through 5; accept available and fully unavailable direct-buffer windows; and
reject missing/extra fields, sample mismatch, impossible availability/value
combinations, or maxima below endpoints. A real `step-12` run must produce
strictly valid schema-10 evidence, followed by the full Java workspace check.

## Rollback

Return new raw evidence to schema 9 and aggregate evidence to schema 4. Keep the
runtime direct-buffer metrics for operations. No product protocol, data,
readiness, admission, client, or deployment behavior changes.
