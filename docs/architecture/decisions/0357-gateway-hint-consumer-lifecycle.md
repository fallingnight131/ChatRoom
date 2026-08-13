# ADR-0357: Gateway Hint Consumer Lifecycle

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The hint consumer pass is deterministic but needs a controlled polling lifecycle
before runtime composition. The lifecycle must never overlap SQL repair, skip a
failed cursor, busy-spin while idle, or export identity labels.

## Decision

- Start explicitly and run one consumer pass at a time from stream ID `0-0` for
  the gateway's new boot stream. Never persist or reuse that cursor across boot
  UUIDs.
- Require every returned stream entry to be strictly later than the request and
  strictly increasing, comparing the unsigned Redis millisecond/sequence pair.
- Drain immediately when a pass reads the configured full batch. Otherwise poll
  after a reviewed 10 ms through 10 second idle interval.
- On a classified repair failure or an unexpected pass exception, retain the
  service-returned preceding cursor and retry with exponential 100 ms through
  five minute backoff. A successful pass resets failure state.
- Reject repeated start, cancel pending work on close, and expose only fixed
  counters/gauges for runs, read, applied, duplicates, no-subscription, failed,
  consecutive failures, and next delay. The stream cursor is deliberately not a
  metric label or externally persisted state.

## Consequences

The gateway can consume a boot-specific bounded stream without concurrent repair
or unbounded polling. Malformed/reordered adapter data fails before any cursor
movement. A poison hint creates visible backoff rather than being skipped.

The loop remains uncomposed. Product activation still requires one owner for the
Redis adapter/scheduler, readiness coupling to lease validity, ordered shutdown,
and a real PostgreSQL+Redis two-gateway integration scenario.

## Verification

Tests prove full-batch immediate drain, failed-entry cursor retention across a
classified failure and subsequent exception, exponential delay and healthy
reset, idle polling, repeated-start rejection, cancellation, strictly increasing
Redis IDs, and identity-free Prometheus rendering.

## Rollback

Do not start the loop. The single-gateway local router remains the product path.
