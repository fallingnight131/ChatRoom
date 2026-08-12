# ADR-0213: Close Web Staged Health and Promotion as One Release Result

- Status: Accepted
- Date: 2026-08-13
- Owners: Web release engineering and operations
- Related milestone: M4
- Extends: ADR-0212

## Context

Workflow ordering alone does not prevent archived preview health, execution,
production health, and completion files from different attempts being combined
later. A release result needs an independently reproducible closure.

## Decision

- Reverify the reviewed preview health window, full promotion completion chain,
  and production health window from their raw observations and immutable
  candidate/rollback roots.
- Require one candidate identity, distinct preview/production origins, preview
  health before execution, production health after execution, and promotion
  completion after the production window.
- Bind the preview window, pointer execution, production window, and promotion
  completion by SHA-256 in a write-once staged-release completion.
- Create and immediately reverify this record before the workflow reports
  production completion. Failure remains inside the existing rollback trigger.

## Consequences

A successful real workflow run can be audited as one staged technical release
rather than a directory of loosely related files. This still does not represent
percentage traffic control, end-user telemetry, or a completed real production
run.

## Verification

- `python3 Tests/web_staged_release_completion_test.py`
- `python3 Tests/web_production_release_workflow_test.py`
