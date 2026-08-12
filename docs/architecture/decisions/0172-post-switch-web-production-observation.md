# ADR-0172: Post-Switch Web Production Observation

- Status: Accepted
- Date: 2026-08-12
- Owners: Web release engineering and operations
- Extends: ADR-0114, ADR-0143, ADR-0171

## Context

Atomic local pointer status cannot prove what a production HTTPS origin, CDN,
or reverse proxy served. Pre-promotion preview observations also cannot be
reused because routing and cache state may change at the switch. Completion
must bind fresh static and application-route observations to the exact execution
without making an unsupported continuous-availability claim.

## Decision

- Require a fresh strict HTTPS static-release observation and application-route
  observation after pointer execution.
- Require both to use the execution's exact HTTPS origin; require the static
  observation to identify its exact release, version, and source revision.
- Require neither observation to precede execution, both to complete within a
  configurable 60-to-900-second window (default 600), and their timestamps to
  remain within five minutes of one another.
- Reverify the full execution/authorization/technical chain and both observation
  schemas before recording completion.
- Write once and bind SHA-256 of execution plus both post-switch observations,
  all three times, candidate/rollback identity, and completion window.
- Label the closed result `production-promotion-observed`.
- Independently reconstruct durable evidence using recorded completion time.

## Consequences

The release chain distinguishes preview readiness, authorization, local pointer
mutation, and externally observed production delivery. Evidence remains a
point-in-time statement: monitoring, staged traffic, branded browsers, and
incident rollback objectives need their own proof.

## Migration and Rollback

Existing pointer-execution evidence remains pending until new post-switch probes
are captured. If completion fails or times out, activate the retained rollback
release, probe it, and create rollback evidence; never relabel preview records.

## Verification

- `python3 Tests/web_release_completion_test.py`
- reject pre-switch, late, future, split-window, wrong-origin/release, changed
  source, unknown/duplicate fields, invalid window, and overwrite
- retain all artifact, release-store, HTTPS, route, promotion, authorization,
  and pointer-execution tests
