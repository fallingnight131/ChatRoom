# ADR-0212: Gate Web Production on Preview and Production Health Windows

- Status: Accepted
- Date: 2026-08-13
- Owners: Web release engineering and operations
- Related milestone: M4
- Extends: ADR-0207, ADR-0211

## Context

The production workflow previously used one preview observation before approval
and one production observation after switching. The health-window contract is
useful only when orchestration actually collects its samples and fails into the
existing rollback path.

## Decision

- Collect three static plus API/WebSocket pairs over at least 60 seconds on
  preview before requesting production approval.
- After approval, revalidate the downloaded evidence and collect a new preview
  window so an old healthy interval cannot authorize a delayed switch.
- After atomic production activation, collect a three-pair production window
  before recording promotion completion.
- Treat production-window or completion failure after pointer execution as a
  rollback trigger under the already pre-authorized B-to-A recovery path.
- Retain every raw observation and health result in the existing 90-day release
  artifact. Do not claim percentage rollout or end-user telemetry.

## Consequences

The workflow now proves bounded repeated technical health when it runs, at the
cost of roughly two additional minutes of observation. The health results are
sequenced but not yet bound into one independently verifiable final release
completion; that is the next closure boundary. No real production run is
claimed.

## Verification

- `python3 Tests/web_production_release_workflow_test.py`
- `python3 Tests/web_release_health_window_test.py`
