# ADR-0145: Web Technical Promotion Record

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

Static release observation, application-route observation, and retained rollback
artifacts are deliberately independent. Without a final decision boundary an
operator or deployment adapter could combine observations from different
origins or release windows, use stale evidence, omit a rollback target, or lose
the exact source records that justified traffic promotion.

## Decision

- Create a closed, write-once technical-promotion record; do not let the tool
  execute provider deployment or claim public traffic was changed.
- Require the candidate immutable release and its strict HTTPS observation, a
  strict application-route observation, a different immutable rollback release,
  and that rollback release's strict prior HTTPS observation.
- Require one exact credential-free HTTPS origin across all observations. The
  candidate static and route observations must be no older than a configurable
  60-to-3600-second bound (default 900 seconds), not from the future, and within
  five minutes of each other.
- A rollback observation may be older because it records the last verified
  state of the retained previous release, but it may not be from the future and
  must still match the exact retained immutable artifact.
- Bind both release identities and artifact-manifest digests plus SHA-256 of all
  three input observations, their observation times, the technical approval
  time, and the freshness policy.
- Label the result `technical-gates-observed-not-published`. Provider
  authorization, staged traffic changes, branded-browser checks, monitoring,
  and business approval remain separate.
- Independently verify a retained record by reconstructing it using its fixed
  approval time and freshness policy. Unknown fields or any changed source byte
  fail closed.

## Consequences

A provider adapter now has one immutable technical input rather than a loose
folder of JSON. The record proves that a candidate, application routes, and a
different rollback artifact were coherently observed; it does not prove that
the provider promoted traffic or that the rollback will meet an operational
recovery objective. An initial launch with no previous release is intentionally
not authorized by this path and needs its own reviewed bootstrap procedure.

## Migration and Rollback

Existing observation files remain valid inputs when they meet the closed schemas
and freshness window. Removing this tool changes no hosted state. A deployment
adapter must stop before mutation if record verification fails.

## Verification

- happy-path evidence binds candidate, routes, rollback target, timestamps, and
  all source-file hashes, then independently verifies;
- changed source records and unknown output fields fail;
- stale, future, split-window, cross-origin, and same-release rollback inputs
  fail closed;
- fixture output remains explicitly not-published.
