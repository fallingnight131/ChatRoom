# ADR-0211: Require Bounded Repeated Web Release Health

- Status: Accepted
- Date: 2026-08-13
- Owners: Web release engineering and operations
- Related milestone: M4
- Extends: ADR-0204, ADR-0207

## Context

One successful static probe and one API/WebSocket probe can occur during a
brief healthy instant and do not establish sustained release health. Waiting
without retaining exact samples is also not auditable.

## Decision

- Define preview and production health windows from 3 to 30 unique, strictly
  ordered pairs of exact static and application-route observations.
- Require every pair to share one HTTPS origin and immutable release identity,
  occur within 30 seconds, span at least 60 seconds and at most 15 minutes, and
  contain no gap over five minutes.
- Bind each source observation by SHA-256 in a write-once result and require
  completion within five minutes of the last sample.
- Treat this as provider-neutral release health only. User experience metrics,
  authenticated journeys, error budgets, and percentage traffic controls remain
  separate rollout concerns.

## Consequences

A release can no longer pass sustained-health policy from one lucky request or
reused evidence. The workflow still needs to invoke this contract before and
after production mutation; the contract alone is not a staged rollout or real
production evidence.

## Verification

- `python3 Tests/web_release_health_window_test.py`
