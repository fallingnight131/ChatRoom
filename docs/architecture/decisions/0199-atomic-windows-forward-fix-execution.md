# ADR-0199: Atomically Execute the Incident-Bound Windows Forward Fix

- Status: Accepted
- Date: 2026-08-13
- Owners: Windows release engineering and operations
- Related milestone: M4
- Extends: ADR-0197, ADR-0198

## Context

The forward-fix authorization proves that release C can repair clients already
on failed B, while the open-incident marker blocks ordinary channel mutation.
A dedicated consumer is required to join those controls to the current A
pointer and immutable store without declaring recovery before public delivery
is observed.

## Decision

- Reconstruct the complete ADR-0197 authorization and ADR-0198 incident record
  at execution time.
- Require the incident to bind the exact B promotion completion and failed B /
  restored A identities, require A to be the current valid pointer, and require
  C at its exact content-addressed pre-staged path.
- Persist a write-once authorization/incident consumption record before channel
  mutation, then atomically compare-and-switch A to C.
- If pointer validation or execution-evidence persistence fails, restore A but
  retain consumption so a fresh authorization and review are required.
- Keep the open-incident marker after local success. Execution evidence is
  `forward-fix-pointer-switched-awaiting-external-observation`; only a later
  strict HTTPS completion step may close the incident.

## Consequences

The general promotion path cannot impersonate incident recovery, and a replayed
forward-fix authorization cannot produce a second mutation. Local pointer
success remains intentionally insufficient to resume ordinary promotions.

## Migration and Rollback

Stage C immutably before execution. On finalization failure the adapter restores
A, leaves the incident open, and requires a new authorization. Provider-backed
implementations must retain equivalent conditional-write and durable-consume
semantics.

## Verification

- `python3 Tests/windows_update_forward_fix_execution_test.py`
- `python3 Tests/windows_update_forward_fix_authorization_test.py`
- `python3 Tests/windows_update_incident_state_test.py`
