# ADR-0103: Inactive Attachment Cleanup Loop and Metrics

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

ADR-0102 provides a bounded cleanup pass and its infrastructure adapters, but a
production background task also needs non-overlapping execution, dependency-
failure backoff, deterministic shutdown, and operator-visible aggregate state.
Starting it before real-provider acceptance would create external side effects
and credential requirements in the otherwise pre-cutover gateway.

## Decision

- Add a manually activated cleanup loop in the gateway operations layer. The
  loop schedules the next pass only after the current pass returns, preventing
  overlap within one process.
- Run immediately after explicit start. Use a bounded healthy interval after a
  clean pass and capped exponential delay after a provider failure,
  confirmation failure, or whole-pass exception. A later deployment composition
  may add bounded jitter to avoid synchronized multi-instance retries.
- Reset consecutive failures only after a completely healthy pass. Partial
  success remains visible in item counters while still triggering backoff.
- Make the loop single-start and closeable. Closing cancels a pending task
  without interrupting an in-flight provider/database operation; a finishing
  in-flight pass observes closure and schedules no successor.
- Export fixed counters for runs, whole-run failures, revoked, attempted,
  deleted, provider failures, and confirmation failures. Export only
  consecutive-failure and next-delay gauges. Never label or log account,
  conversation, device, attachment, filename, object key, signed URL, endpoint,
  or credential.
- Add the metric family to the existing exact-path loopback `/metrics` response.
  Create telemetry in the gateway composition root, but do not construct or
  start the cleanup loop yet; metrics therefore remain zero until activation.
- Require the real provider capability acceptance from ADR-0099 before a later
  ADR may compose S3 runtime, PostgreSQL cleanup, scheduler ownership, and
  shutdown into `GatewayRuntime`.

## Consequences

The operational contract is testable without cloud access or background threads
in ordinary gateway tests. The scheduler executor remains owned by the future
composition root, allowing bounded thread naming and reverse-order shutdown.

Process-local non-overlap does not coordinate multiple gateway instances. The
PostgreSQL `SKIP LOCKED` revoke operation and idempotent provider delete preserve
correctness, while jitter and deployment ownership remain activation concerns.

## Verification and Rollback

Deterministic scheduler tests prove immediate start, one pending task, healthy
interval, partial-failure and exception backoff, cap, recovery reset,
single-start, cancellation, and no reschedule after close. Telemetry/Prometheus
tests prove exact fixed outcomes and absence of identity labels. Full admin
endpoint tests prove the new family is served with existing safety headers.

Rollback removes the inactive loop, policy, telemetry/renderer, and admin metric
concatenation. It changes no provider, database, product listener, protocol, or
credential behavior.
