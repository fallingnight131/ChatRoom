# ADR-0182: Observed Windows Update Promotion Completion

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and operations
- Related milestone: M4
- Extends: ADR-0180, ADR-0181

## Context

Pointer execution is pending evidence, and an HTTPS observation alone does not
prove it happened after the authorized switch. Release completion needs one
closed record that binds both boundaries and rejects stale, pre-switch, or
identity-mismatched observations.

## Decision

- Add write-once schema-1 Windows update production-promotion completion
  evidence.
- Independently reconstruct the authorization, atomic execution, target and
  rollback candidates, expected-current manifest, and HTTPS observation.
- Require exact channel, release manifest SHA, sequence, version, and source
  revision equality between execution and observation.
- Require observation at or after execution and completion within a configurable
  60–900-second window, defaulting to ten minutes. Reject future observations
  beyond one minute of clock tolerance.
- Bind execution and observation files by SHA-256 plus their exact timestamps,
  target/rollback IDs, manifest URL, and release identity.
- Mark the result `production-update-promotion-observed`. Treat it as
  point-in-time publication evidence, not continuous availability, global CDN
  convergence, update installation success, or rollout health.

## Consequences

The M4 update channel now has distinct signed-candidate, authorization,
execution, observation, and completion states. Operational tooling can trigger
rollout monitoring only after completion rather than after a local pointer
write. More than one observation location remains an operations policy rather
than an unsupported claim in this evidence schema.

## Migration and Rollback

No existing state changes while recording completion. Invalid or late evidence
requires fresh observation and, after the execution window closes, a new
authorized promotion attempt or incident decision. Recorded completion is
immutable and is an input to the future exact rollback consumer.

## Verification

- `python3 Tests/windows_update_release_completion_test.py`
- reject pre-switch/late/future observation, expired completion, identity or
  input mutation, duplicate/unknown fields, invalid windows, and overwrite;
- production origin and client install/rollout monitoring remain release gates.
