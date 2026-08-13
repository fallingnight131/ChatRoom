# ADR-0391: Portable Process Resource Metrics

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

Worker, PostgreSQL, and event-loop signals cannot explain host CPU pressure or
JVM heap growth. The gateway must remain deployable on Linux even though current
development is on macOS, so the first process resource surface cannot depend on
platform-specific commands or native libraries.

## Decision

- Export cumulative process CPU seconds and explicit CPU-time availability from
  `ProcessHandle.Info.totalCpuDuration()`.
- Export JVM heap used, committed, and maximum bytes from Java management/runtime
  APIs, plus process uptime and available processors.
- Capture metrics only when the loopback `/metrics` endpoint is read; do not add
  a background resource-sampling thread.
- Add no host, PID, thread, user, endpoint, or container labels. Do not change
  readiness, admission, or JVM sizing.
- Defer RSS, GC-pause distributions, container CPU quota, and cgroup memory to a
  separately justified portable implementation.

## Consequences

Operators and benchmarks can derive CPU time consumed across a window and heap
growth using portable Java APIs. Cumulative CPU time is not CPU percentage;
percentage requires elapsed wall time and available-processor context.

Heap used is an instantaneous post-allocation observation and may change with
GC. The maximum uses the runtime effective maximum. These metrics do not expose
off-heap/native memory or resident set size.

## Verification

Unit tests require exact fixed names, seconds conversion, heap invariants, and
explicit unavailable CPU behavior. The real loopback endpoint test verifies the
composed values while full gateway tests preserve health and other metrics.

## Rollback

Remove the process-resource supplier and renderer. No product protocol, data
model, readiness, admission, or runtime configuration changes.
