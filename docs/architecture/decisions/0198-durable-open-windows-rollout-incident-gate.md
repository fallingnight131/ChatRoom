# ADR-0198: Gate Windows Channel Mutation on a Durable Open Incident

- Status: Accepted
- Date: 2026-08-13
- Owners: Windows release engineering and operations
- Related milestone: M4
- Extends: ADR-0183, ADR-0192, ADR-0197

## Context

After B is halted and A is restored, ordinary promotion still permits a new
version/source. That is normally correct, but during an unresolved incident it
could bypass the stricter B-compatible, 100-percent forward-fix authorization.
The channel store previously retained rollback consumption without exposing an
authoritative open-incident gate to every mutation path.

## Decision

- Before the B-to-A pointer restoration, create an immutable rollout-incident
  record bound to the exact observed B promotion completion, failed release,
  restored release, channel, and UTC opening time.
- Hard-link that retained record to one exclusive active-incident marker in the
  same store. Existing, linked, malformed, future, missing-retention, or byte-
  divergent state fails closed.
- Make ordinary release-promotion and rollout-expansion executors reject all
  channel mutation while the marker is open, before writing their consumption
  records.
- Reserve incident resolution for the dedicated forward-fix executor. This
  step does not remove or resolve the marker.

## Consequences

An operator cannot route around incident recovery by presenting an otherwise
valid general promotion or expansion authorization. If rollback finalization
fails after the marker opens, the channel remains deliberately blocked for
manual evidence review rather than silently returning to ordinary delivery.

## Migration and Rollback

Existing stores have no open marker and continue normally. Do not delete an
open marker to recover service. The next step must consume ADR-0197, activate
the exact forward fix once, and retain a closed incident record. Provider
adapters must offer equivalent exclusive incident state and conditional
mutation semantics.

## Verification

- `python3 Tests/windows_update_incident_state_test.py`
- `python3 Tests/windows_update_release_rollback_test.py`
- `python3 Tests/windows_update_release_execution_test.py`
- `python3 Tests/windows_update_rollout_expansion_execution_test.py`
