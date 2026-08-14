# ADR-0399: Process RSS Provider Boundary

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The Java gateway now reports heap and GC collection activity, but heap excludes
class metadata, thread stacks, Netty direct buffers, code cache, native
libraries, and allocator overhead. Process resident set size (RSS), or the
closest platform working-set equivalent, is needed to observe the whole gateway
process footprint.

Java 21 has no standard API for current process RSS. Linux exposes `VmRSS`,
macOS exposes task resident size through `proc_pidinfo`, and Windows exposes
working-set size through `GetProcessMemoryInfo`. Shelling out to `ps` or
PowerShell on every `/metrics` scrape or five-millisecond benchmark sample would
distort the workload and create an operational dependency.

The product-client support scope is Web and Windows. That does not imply the
Java server is a supported Windows deployment target; server deployment-host
support and client product support are separate decisions.

## Decision

- Define the metric semantic as current resident/working-set bytes owned by the
  gateway process. Do not mix virtual memory, committed heap, container memory,
  or host memory into this value.
- Add a process-RSS provider boundary with a snapshot containing:
  availability, resident bytes, monotonic sample age, and cumulative read
  failures. The provider source is fixed by runtime composition, not a
  high-cardinality metric label.
- Export fixed-name loopback metrics only after implementation:
  - `chat_gateway_process_resident_memory_available`;
  - `chat_gateway_process_resident_memory_bytes`;
  - `chat_gateway_process_resident_memory_sample_age_seconds`;
  - `chat_gateway_process_resident_memory_read_failures_total`.
- Refresh through one lifecycle-owned, bounded sampler no faster than 250 ms.
  `/metrics` and reconnect evidence read the cached snapshot; they never launch
  an operating-system command or perform native I/O synchronously.
- Implement providers in this order:
  1. Linux server provider reading `/proc/self/status` `VmRSS` with strict
     `kB`-to-byte parsing and bounded file size;
  2. macOS development/evidence provider using `proc_pidinfo` through a reviewed
     native bridge;
  3. Windows server provider using `GetProcessMemoryInfo` only if Windows Java
     server deployment becomes an explicit target.
- Keep an always-unavailable provider for unsupported or failed composition.
  Unavailable snapshots export zero resident bytes and do not affect readiness.
- Do not add JNA, OSHI, JNI, or another native dependency to the core gateway
  merely to make the metric appear cross-platform. A native bridge requires a
  separate dependency, packaging, security, and platform test decision.
- Benchmark evidence must record provider availability, sampling period, read
  failures, before/after/peak cached RSS, and sample age. An unavailable provider
  cannot support an RSS capacity claim.

## Consequences

The architecture separates a stable observation contract from platform
mechanics and avoids per-scrape subprocess overhead. Linux can gain a
dependency-free provider first, while macOS local development and any future
Windows server support remain honest about missing native evidence.

RSS values remain affected by shared pages, allocator behavior, kernel
accounting, and container semantics. A 250 ms cache cannot attribute very short
peaks. Container memory current/limit, native allocation categories, exact GC
pauses, and production capacity remain separate signals.

## Verification

Provider contract tests must cover unavailable/available invariants, stale age,
failure retention, overflow-safe unit conversion, malformed/truncated files,
and sampler shutdown. Linux integration must compare a non-zero self `VmRSS`
read with `/proc` availability. macOS and Windows providers require native host
tests before their availability flag may be true.

Reconnect evidence must remain valid when RSS is unavailable and must state that
the dimension is unmeasured. Clean RSS-aware baseline claims require an
available provider on the exact evidence host.

## Rollback

Compose the always-unavailable provider and omit RSS-aware evidence fields.
CPU, heap, GC, queue, PostgreSQL, event-loop, readiness, protocol, data, and
client behavior remain unchanged.
