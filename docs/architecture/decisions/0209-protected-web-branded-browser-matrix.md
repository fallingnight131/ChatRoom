# ADR-0209: Run Branded Web Support on Version-Dedicated Hosts

- Status: Accepted
- Date: 2026-08-13
- Owners: Web release engineering and quality
- Related milestone: M4
- Extends: ADR-0208

## Context

Automatically installing a channel named `stable` during a release run can
silently change the tested version. A generic browser runner also cannot keep a
current and previous stable binary reliably isolated. The release gate needs
reviewable inputs without giving browser tests deployment authority.

## Decision

- Run six jobs on dedicated x86_64 Linux host labels, one for each branded
  current/previous Chrome, Edge, and Firefox slot.
- Require the protected `web-browser-support` environment to review the exact
  candidate artifact, browser versions, and executable SHA-256 values.
- Use a preinstalled, non-symlink executable supplied by each host. Never
  download or update a browser in the acceptance workflow; hash it before and
  after the test and configure Playwright with its exact path.
- Serve the immutable candidate payload locally, record runtime version/user
  agent and smoke checks from that browser, independently verify the record,
  and retain it for 30 days.
- Grant only source/artifact read permission. The matrix has no Web production,
  package publication, browser installation, or release mutation authority.

## Consequences

Operations must maintain six isolated browser hosts and update their reviewed
binaries deliberately. A workflow definition is not a completed matrix; all
six records still need an aggregate closure against the same candidate before
the browser support claim can advance.

## Verification

- `python3 Tests/web_browser_support_matrix_workflow_test.py`
- `python3 Tests/web_browser_host_evidence_test.py`
