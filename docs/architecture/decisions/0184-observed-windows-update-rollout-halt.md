# ADR-0184: Observed Windows Update Rollout Halt

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and incident operations
- Related milestone: M4
- Extends: ADR-0181, ADR-0183

## Context

Restoring the local/provider pointer to A does not prove that caches and public
routing stopped serving B. Incident closure requires the same client-visible
HTTPS byte evidence used for promotion, but bound to the exact rollout-halt
execution and restored release.

## Decision

- Add immutable schema-1 rollout-halt completion evidence.
- Reconstruct the full promotion completion, B→A rollback execution, both
  retained candidates, and a new ADR-0181 observation of restored A.
- Require A channel, manifest SHA, sequence, version, and source revision to
  match rollback evidence exactly.
- Require A observation at or after rollback and completion within 60–900
  seconds, default ten minutes.
- Bind rollback execution and restored observation by SHA-256 with exact B/A
  IDs, A sequence/version/source, manifest URL, and timestamps.
- Mark the result `production-update-rollout-halt-observed`. Do not reinterpret
  it as downgrade of clients that already accepted B.

## Consequences

Incident operations can prove both the conditional pointer reversal and what an
unaffected client subsequently fetched. The record remains point-in-time and
does not replace forward-fix delivery, multi-region monitoring, or telemetry
from devices already running B.

## Migration and Rollback

Recording completion changes no channel state. A late or mismatched observation
cannot be attached to the incident; gather fresh evidence within policy or
escalate to a forward corrective release. Completion records are never edited.

## Verification

- `python3 Tests/windows_update_rollback_completion_test.py`
- reject late, wrong-release/sequence, mutated, duplicate, unknown, invalid
  window, and overwrite cases;
- production origin evidence remains required at release/incident time.
