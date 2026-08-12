# ADR-0204: Complete Web Rollback with Static and Application-Route Observation

- Status: Accepted
- Date: 2026-08-13
- Owners: Web release engineering and operations
- Related milestone: M4
- Extends: ADR-0142, ADR-0173

## Context

The Web rollback executor restores exact A, while the earlier generic A/B/A
evidence compares static observations only. It does not prove that a particular
rollback execution was followed by restored production `/api/health` and `/ws`
routing, nor bound recovery to an incident window.

## Decision

- Reconstruct the complete pre-authorized B-to-A rollback execution.
- After pointer restoration, require a fresh strict HTTPS observation of exact
  immutable A plus a fresh same-origin API-health and nonce-bound WebSocket
  upgrade observation.
- Bind both observations to the production origin, restored release ID, rollback
  execution SHA-256, and 60-to-900-second completion window. Observations must
  occur after rollback and within five minutes of each other.
- Persist and independently reconstruct one write-once
  `production-rollback-observed` result. Keep generic A/B/A evidence as useful
  historical/no-rebuild rehearsal, but do not use it alone to close an incident.

## Consequences

A successful pointer write or static-only response no longer establishes Web
rollback recovery. The result proves a point-in-time static and route recovery,
not authenticated chat behavior, global edge convergence, or continuous health.

## Verification

- `python3 Tests/web_release_rollback_completion_test.py`
- `python3 Tests/web_release_rollback_execution_test.py`
