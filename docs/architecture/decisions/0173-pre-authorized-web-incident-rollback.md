# ADR-0173: Pre-Authorized Web Incident Rollback

- Status: Accepted
- Date: 2026-08-12
- Owners: Web release engineering and operations
- Extends: ADR-0142, ADR-0170, ADR-0171

## Context

An unhealthy production promotion needs a fast deterministic rollback after its
short-lived promotion authorization may have expired. Requesting a new arbitrary
release choice during an incident increases recovery time and risks selecting
unverified bytes. Promotion execution already durably binds the candidate B and
retained rollback A. Rollback evidence persistence can fail after the safer
pointer has been restored; undoing that restoration would reactivate failed B.

## Decision

- Treat durable promotion-execution evidence as pre-authorization only for its
  exact B→A rollback pair.
- Reverify the complete execution, authorization, technical promotion, immutable
  B/A releases, and preview observations before mutation.
- Require B to be the current active pointer and both release paths to be their
  exact directories in the release store.
- Persist a one-time execution-digest rollback marker before mutation.
- Atomically activate A and verify local store health.
- Write closed evidence labeled
  `rollback-pointer-restored-awaiting-external-observation`.
- If evidence persistence fails after A is active, leave A active and retain the
  consumption marker; never automatically reactivate B.
- Require a fresh external observation of restored A and the existing
  A-before/B/A-restored evidence before declaring incident recovery complete.

## Consequences

Rollback is fast, closed to a pre-reviewed target, and non-replayable without
granting an operator arbitrary release selection. Local pointer recovery remains
separate from public/CDN recovery evidence. A failed evidence write requires
manual investigation but preserves the safer release.

## Migration and Rollback

Only promotions executed through ADR-0171 have the required durable input.
Older/manual pointer changes use the documented manual runbook and cannot claim
this evidence. Removing the tool does not change the current pointer.

## Verification

- `python3 Tests/web_release_rollback_execution_test.py`
- prove exact restoration, replay rejection, wrong-current rejection, safer-A
  retention after evidence failure, and mutated/duplicate/backdated rejection
- retain complete Web promotion, completion, and rollback-evidence suites
