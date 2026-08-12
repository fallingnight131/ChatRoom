# ADR-0210: Close the Branded Browser Matrix as One Candidate Result

- Status: Accepted
- Date: 2026-08-13
- Owners: Web release engineering and quality
- Related milestone: M4
- Extends: ADR-0208, ADR-0209

## Context

Six individually valid records can still describe different Web candidates,
unpaired version windows, or mutated evidence. Artifact names and a successful
matrix UI are not a durable support result.

## Decision

- Require exactly the six policy slots and their exact approved versions and
  executable hashes; reject missing, duplicate, stale, or unexpected records.
- Require each family's current version to be strictly newer than its previous
  version and independently revalidate every record against the same immutable
  candidate.
- Produce a write-once completion that binds release identity, candidate
  manifest SHA-256, ordered slot identities, host platform/architecture, browser
  versions and hashes, and the SHA-256 of every source record.
- Run closure on a separate Ubuntu job only after all six protected hosts pass,
  redownload the exact candidate, reverify the completion, and retain it for 90
  days. Closure has no deployment or publication authority.

## Consequences

A real completed workflow can provide one auditable branded-browser smoke
matrix. It still does not prove authenticated, offline, accessibility, media, or
production-origin behavior, so M4 Web publication remains open.

## Verification

- `python3 Tests/web_browser_matrix_completion_test.py`
- `python3 Tests/web_browser_support_matrix_workflow_test.py`
