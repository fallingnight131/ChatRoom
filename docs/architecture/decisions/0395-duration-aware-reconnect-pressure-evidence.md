# ADR-0395: Duration-Aware Reconnect Pressure Evidence

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The clean ADR-0394 ladder first met the repeated peak-pressure rule at
`step-24`, driven by PostgreSQL waiter and Netty pending-task maxima. Peak-only
evidence cannot distinguish one five-millisecond observation from a sustained
queue. That ambiguity is too large to justify a stronger pressure-knee or
capacity interpretation.

The shared sampler already reads the relevant fixed-name gauges at a target
five-millisecond cadence. Extending the evidence producer can add duration
context without changing gateway runtime behavior, readiness, admission, or
metrics cardinality.

## Decision

- Upgrade new raw dual-edge reconnect evidence to schema version 7 while
  preserving schemas 1 through 6 as historical contracts.
- In the existing shared sampling window, count positive samples and the
  longest consecutive positive-sample streak for:
  - authentication queued work;
  - PostgreSQL threads awaiting a connection;
  - aggregate Netty event-loop pending tasks.
- Record the six counters in a `pressureDuration` block with the same sample
  interval and sample count as the existing resource blocks.
- Require exact reconciliation: positive samples cannot exceed the window;
  longest streak cannot exceed positive samples; and a zero peak, zero positive
  count, and zero streak must agree in both directions.
- Keep the repeated ladder validator compatible with uniform schema-6 or
  uniform schema-7 children. Reject mixed child schemas so one aggregate cannot
  silently compare different observation contracts.
- Do not change the ADR-0394 peak-based aggregate conclusion in this slice.
  Duration-aware classification requires a separately versioned aggregate rule.

## Consequences

New raw evidence can distinguish isolated from consecutive sampled pressure.
For example, a PostgreSQL waiter peak of 2 with one positive sample and a
one-sample streak is explicitly transient at the sampler's resolution.

The counters approximate sampled duration, not exact event duration. HTTP
metrics reads and host scheduling may miss shorter transitions, and the target
five-millisecond cadence is not guaranteed. Queue magnitude over time, GC, RSS,
host utilization, and production capacity remain unproven.

## Verification

Contract tests preserve schemas 1 through 7 and reject missing fields,
bad sample counts, impossible streaks, peak/duration disagreement, unexpected
extensions, and mixed ladder child schemas. A real `step-12` run must emit and
strictly validate schema-7 evidence before this slice is committed.

## Rollback

Return new raw evidence to schema version 6 and omit `pressureDuration`.
Historical evidence, gateway runtime metrics, production protocols, data,
admission, readiness, and deployment behavior remain unchanged.
