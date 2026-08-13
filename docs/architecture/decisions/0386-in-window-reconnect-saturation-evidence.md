# ADR-0386: In-Window Reconnect Saturation Evidence

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0385 exposes active and queued authentication work, but values read only
before and after a reconnect curve can miss a transient peak. The bounded
dual-edge scenario already records recovery latency, scheduling jitter, and
authentication totals. It needs a window-bound saturation observation before
we can distinguish an idle authentication executor from a saturated one.

## Decision

- Start a loopback metrics sampler before releasing the reconnect clients and
  stop it only after every reconnect future completes.
- Poll the secondary gateway every five milliseconds and record the number of
  successful samples plus maximum active authentication workers and queued
  authentication work.
- Upgrade new dual-edge evidence to schema version 2. Keep the validator able
  to read schema version 1 so the exact-revision historical baseline remains
  valid.
- Require schema version 2 evidence to contain at least two samples and to
  observe at least one active authentication worker. Bind the sample interval
  and upper bounds to the existing 12-session workload.

## Consequences

The local recovery curve can now state whether this bounded run visibly queued
on the authentication executor. The polling itself adds loopback admin traffic,
so comparisons must retain the same five-millisecond interval.

This does not measure PostgreSQL pool use, query wait, Netty event-loop lag,
CPU, memory, Redis latency, or production fleet capacity. A zero queue peak is
evidence only for this workload and host.

## Verification

Python contract tests cover historical schema version 1, complete schema
version 2, and missing, malformed, or out-of-bounds saturation records. The
real two-edge harness must produce schema version 2 evidence that passes the
strict validator at its exact clean revision.

## Rollback

Stop emitting schema version 2 and retain schema version 1 validation. No
product protocol, persistent data, routing, readiness, or admission behavior
changes.
