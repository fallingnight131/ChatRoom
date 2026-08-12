# ADR-0171: Authorized Atomic Web Pointer Execution

- Status: Accepted
- Date: 2026-08-12
- Owners: Web release engineering and operations
- Extends: ADR-0113, ADR-0145, ADR-0170

## Context

A valid authorization still needs a narrowly scoped consumer. Calling the
general release-store activation command by hand would not enforce expected
current state, prevent replay, persist execution evidence, or restore the prior
pointer if local verification fails. Conversely, pointer state cannot prove
that a CDN or public browser observed the release.

## Decision

- Implement a provider-neutral execution adapter only for hosting layers that
  consume the repository's atomic `active-release.json` contract.
- Reverify the unexpired production authorization and all underlying technical
  inputs immediately before mutation.
- Require both immutable release roots to be their exact authorized paths in
  the named store, and require the current active release to equal the authorized
  rollback target.
- Persist an exclusive authorization-digest consumption marker before changing
  the pointer. A consumed authorization cannot be retried, including after a
  partial failure; obtain fresh observations and authorization instead.
- Atomically activate the candidate and verify local release-store health.
- If activation, status, or write-once evidence persistence fails, atomically
  restore the authorized rollback pointer and preserve the consumption marker.
- Emit closed evidence labeled
  `pointer-switched-awaiting-external-observation`; independently reconstruct it
  against the authorization at execution time.
- Make no CDN, DNS, cloud-provider, browser, or application request.

## Consequences

Pointer mutation is constrained by authorization, expected current state, and
replay prevention, with a deterministic local rollback path. Failed attempts
require deliberate reauthorization. The evidence does not claim public success;
post-switch static and application-route observations remain mandatory.

## Migration and Rollback

The adapter applies only where the hosting layer already follows the atomic
pointer contract. Other providers require separate reviewed adapters that
preserve the same authorization, compare-and-swap, replay, rollback, and
evidence semantics. Removing this adapter leaves immutable staged releases and
the previous pointer intact.

## Verification

- `python3 Tests/web_release_execution_test.py`
- require expected-current state and exact in-store release paths
- prove one successful switch, replay rejection, wrong-current-state rejection,
  rollback after evidence failure, and evidence mutation/duplicate-key rejection
- retain the full Web artifact, HTTPS, route, promotion, and authorization suite
