# ADR-0203: Close the Windows Client Support Matrix as One Candidate Transition

- Status: Accepted
- Date: 2026-08-13
- Owners: Windows release engineering and quality
- Related milestone: M4
- Extends: ADR-0201, ADR-0202

## Context

Three independently passing host records can still describe different
candidate versions, sources, channels, publishers, or reruns. Treating such a
collection as one support result would let partial evidence from unrelated
releases satisfy the matrix.

## Decision

- After every native host succeeds, download all three records and both signed
  candidates into a separate Ubuntu verification job.
- Revalidate every host record against the same explicit prior/current
  candidate roots, versions, revisions, channel, Qt version, signer, and support
  policy.
- Require exactly one record for every policy target in deterministic order and
  retain each record's SHA-256 plus observed OS identity.
- Write and immediately reverify one immutable
  `all-supported-windows-client-targets-observed` completion record. Retain it
  longer than individual job evidence for release audit.

## Consequences

No single Windows host and no mixture of historical jobs can establish the
support matrix. The completion record remains evidence for one candidate
transition only; a later release needs a new matrix according to compatibility
policy and does not inherit success merely from the same OS builds.

## Verification

- `python3 Tests/windows_support_matrix_completion_test.py`
- `python3 Tests/windows_support_matrix_workflow_test.py`
