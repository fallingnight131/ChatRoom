# ADR-0133: Durable Windows Update Lifecycle

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The client must exit before the launcher can install, so an in-memory request
cannot bind the result observed after restart. Persisting helper-supplied paths
would let a modified state file redirect reads or cleanup outside update roots.

## Decision

- Add an inactive lifecycle repository with three distinct absolute,
  non-symlink, owner-only roots for state, launcher results, and staged runs.
- Before normal client exit, atomically record exactly one schema-1 pending
  request containing canonical UUID, target semantic version, and UTC creation
  time. Require its matching staged run directory and reject an existing pending
  record or result.
- Derive `result-<uuid>.json` and `run-<uuid>` from the validated UUID; never
  persist or trust arbitrary cleanup paths.
- On startup, hold a lifecycle lock, parse only the UUID-bound ADR-0132 result,
  acknowledge it by removing pending state, then best-effort remove the consumed
  result and run directory. A second consumption returns none.
- Missing result remains pending. Unsafe or invalid evidence fails closed and is
  retained for diagnosis; it is not reported as install success.
- Keep the repository inactive until the consent/shutdown coordinator can record
  pending state after helper handshake and before application quit.

## Consequences

Install outcome survives the process boundary and becomes exactly-once client
state. Failed cleanup cannot redirect outside UUID-derived roots. Stale pending
requests and active-helper-aware aging still require a separate maintenance
policy; this change deliberately does not delete a missing-result run.

## Migration and Rollback

No product path is active. Rollback removes the repository and tests. Once
activated, pending schema changes require compatible reading or explicit
quarantine because copied schema-1 helpers may finish after an application
upgrade.

## Verification

Portable filesystem tests cover atomic single-pending creation, pending result,
UUID-bound successful consumption, replay prevention, run cleanup, and retention
of invalid evidence. Full Qt verification builds the repository into the client.
