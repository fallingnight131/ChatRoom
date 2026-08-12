# ADR-0192: Block General Windows Rollout Expansion

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering, operations, and security
- Related milestone: M4
- Extends: ADR-0178, ADR-0191

## Context

ADR-0191 evaluates rollout health but is intentionally advisory. The existing
general promotion authorization accepted any strictly advancing manifest
sequence, so an operator could submit the same version/source with a different
rollout percentage and bypass the future health-bound expansion path.

## Decision

- Advance general Windows promotion authorization to schema 2 and record both
  target and expected-current rollout percentages.
- Continue allowing a higher-sequence manifest with an unchanged rollout
  percentage, including existing release and recovery workflows.
- Reject any percentage change when target and current manifest have the same
  version and source revision. Such a change is rollout expansion/contraction,
  not a general release promotion, and requires a dedicated health-bound
  authorization.
- Continue allowing a genuinely new version/source to enter its signed initial
  rollout through general promotion authorization.
- Add no metrics trust, signing, network, or mutation behavior in this step.

## Consequences

An advisory `expand-eligible` result cannot be converted into wider exposure by
calling the older generic API. Staged rollout is fail-closed until its dedicated
authorization reconstructs health and metrics provenance. Forward corrective
releases remain possible because they carry a new version/source and higher
sequence.

## Migration and Rollback

Current tooling rejects schema-1 authorization records. Unused records must be
re-created as schema 2. Do not roll back this guard after rollout health is in
operational use; doing so restores an explicit policy bypass.

## Verification

- `python3 Tests/windows_update_release_authorization_test.py`
- existing execution, completion, halt, and rollback regression suites.
