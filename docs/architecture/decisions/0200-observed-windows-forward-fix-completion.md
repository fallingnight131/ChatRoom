# ADR-0200: Complete Windows Forward Fix Only After External Observation

- Status: Accepted
- Date: 2026-08-13
- Owners: Windows release engineering and operations
- Related milestone: M4
- Extends: ADR-0181, ADR-0199

## Context

Local A-to-C pointer execution does not prove that clients receive C through the
production HTTPS endpoint. Closing the incident at local mutation time would
also reopen ordinary promotions while caches or provider routing still serve A.

## Decision

- Require a strict ADR-0181 observation of C's exact canonical manifest,
  detached signature, and Setup bytes after execution and within 60–900 seconds.
- Bind channel, release ID, version, source, manifest sequence, execution,
  observation, incident, and completion times in immutable evidence with status
  `production-forward-fix-observed`.
- Require C to remain the fully validated active pointer and the exact incident
  to remain open while completion is recorded.
- Retain a resolved incident record bound to the execution and completion
  SHA-256 values, then remove only the exclusive open marker. Divergent partial
  resolution fails closed and can be retried only with identical evidence.

## Consequences

Ordinary delivery resumes only after public point-in-time evidence confirms C.
This does not claim global CDN convergence, successful installation on every B
device, continuous availability, or post-fix health.

## Verification

- `python3 Tests/windows_update_forward_fix_completion_test.py`
- `python3 Tests/windows_update_forward_fix_execution_test.py`
- `python3 Tests/windows_update_incident_state_test.py`
