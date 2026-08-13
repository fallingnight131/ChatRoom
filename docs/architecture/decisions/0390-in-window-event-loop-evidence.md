# ADR-0390: In-Window Event-Loop Evidence

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0389 exposes Netty event-loop probes, but a since-start maximum alone cannot
show whether a peak changed during the bounded reconnect window. The shared
five-millisecond admin sampler already captures authentication and PostgreSQL
pool pressure and can read event-loop metrics without another request.

## Decision

- Extend each shared reconnect snapshot with event-loop metrics availability,
  worker count, total probe samples, latest maximum lag, since-start maximum lag,
  and aggregate pending tasks.
- Record probe totals before and after the window and their exact delta so the
  evidence proves the 50 ms product probes progressed during measurement.
- Record the maximum latest lag and pending-task observations across admin
  samples. Record the since-start maximum at the first and last available
  snapshots without claiming the initial value belongs to the recovery window.
- Upgrade new evidence to schema version 4. Preserve schema versions 1 through 3
  as historical contracts and reject the event-loop block in earlier schemas.
- Bind schema version 4 to four product workers, full metrics availability,
  matching shared-sample counts, positive probe progress, and conservative
  bounds that reject corrupt local evidence rather than define an SLO.

## Consequences

The bounded recovery curve can now correlate client latency with authentication
workers, PostgreSQL pool pressure, and observable Netty loop lag/backlog in one
window. Event probes remain 50 ms while admin snapshots target 5 ms; repeated
admin samples may therefore observe the same latest probe value.

The data does not attribute lag to a handler, measure CPU or memory, prove that a
stall shorter than the probe period did not occur, or establish production
capacity.

## Verification

Contract tests cover schema versions 1 through 4 plus missing, unavailable,
stalled, unreconciled, malformed, and out-of-bounds event-loop evidence. The
real dual-edge harness must pass the strict validator before a clean exact-
revision schema version 4 result is committed.

## Rollback

Return new evidence to schema version 3 and stop emitting the event-loop block.
Runtime metrics from ADR-0389 remain. No product protocol, data model, readiness,
admission, or event-loop configuration changes.
