# ADR-0197: Authorize a Windows Forward Fix After Rollout Halt

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and security
- Related milestone: M4
- Extends: ADR-0183, ADR-0184, ADR-0196

## Context

Restoring release A at the channel endpoint stops release B from reaching more
devices, but clients that already accepted B's higher manifest sequence cannot
consume A. They need a new release C. Treating C as an ordinary promotion does
not prove that B clients can authenticate or select it and does not express the
incident recovery intent.

## Decision

- Create a dedicated, 60-to-900-second, write-once authorization only after the
  B-to-A rollback and its strict external observation have completed.
- Reconstruct the entire immutable rollback-completion chain and revalidate A,
  B, and the complete unpublished C candidate.
- Require C to have a greater numeric version and manifest sequence than B, a
  different source revision, and exactly 100 percent rollout.
- Require C's minimum updatable version to include B and require C's manifest
  key ID and exact public PEM to be present in B's compiled primary/secondary
  trust set.
- Bind exact failed, restored, and target release IDs; candidate/manifest
  digests; publisher; Qt version; source; update key; and approval expiry.
- Keep authorization creation credential-free and side-effect-free. It neither
  stages nor activates C.

## Consequences

The forward repair is explicit and demonstrably consumable by devices stranded
on B. A partial rollout, stale sequence, same-version rebuild, restrictive
minimum version, or newly introduced untrusted key fails closed. This record is
not evidence that C was staged, published, observed, installed, or healthy.

## Migration and Rollback

Use this authorization only for the next higher-version corrective release
after an observed rollout halt. A later executor must consume it once and bind
it to a durable incident marker before changing the active pointer. If the
authorization expires, create a new record from unchanged, reverified inputs.

## Verification

- `python3 Tests/windows_update_forward_fix_authorization_test.py`
- `python3 Tests/windows_update_rollback_completion_test.py`
- `python3 Tests/windows_update_channel_candidate_test.py`
