# ADR-0195: Observed Windows Rollout Expansion Completion

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and operations
- Related milestone: M4
- Extends: ADR-0181, ADR-0194

## Context

Expansion pointer execution is local pending evidence. It does not prove that a
trusted client-facing origin serves the higher-sequence manifest, detached
signature, and unchanged Setup bytes after the switch.

## Decision

- Reuse the strict trusted-HTTPS observation from ADR-0181 against the exact
  expansion target candidate.
- Add write-once rollout-expansion completion evidence that reconstructs the
  expansion authorization and execution before accepting observation.
- Require exact channel, release digest, version, source revision, manifest
  sequence, target URL, and target candidate bytes.
- Require observation at or after execution and completion within a 60–900
  second window, defaulting to ten minutes. Reject future, pre-switch, late,
  changed, duplicate, or unknown evidence.
- Retain current/target rollout percentages and seed beside target/rollback IDs,
  execution/observation hashes, and timestamps.
- Mark the result `production-rollout-expansion-observed`. It is point-in-time
  evidence, not continuous CDN convergence or client install/crash health.

## Consequences

An expansion step is not complete merely because a local pointer changed. The
closed record proves the exact higher-sequence percentage manifest and unchanged
installer were externally visible at one instant. The next percentage still
needs a new metrics window and authorization.

## Migration and Rollback

Recording completion does not mutate the channel. A missing/late observation
requires a fresh operational decision; an unhealthy expansion follows the
evidence-derived rollout-halt and forward-fix rules rather than editing this
record.

## Verification

- `python3 Tests/windows_update_rollout_expansion_completion_test.py`
- retain the strict HTTPS probe suite in
  `python3 Tests/windows_update_release_probe_test.py`.
