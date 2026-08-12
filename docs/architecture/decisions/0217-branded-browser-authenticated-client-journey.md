# ADR-0217: Add an Authenticated Client Journey to Branded Browser Evidence

- Status: Superseded by ADR-0218
- Date: 2026-08-13
- Owners: Web client and quality
- Related milestone: M4
- Supersedes: ADR-0216 evidence schema 3

## Context

Schema 3 covered only the unauthenticated login surface. It did not prove that a
valid V1 success response reaches the supported chat shell, that follow-up
directory responses render, that credentials stay out of browser storage, or
that an authenticated client preserves its visible state through offline
recovery.

A browser compatibility test must remain deterministic and must not pretend to
authenticate identity or authorize data on behalf of the server.

## Decision

- Advance branded-browser host evidence to schema 4.
- Install an in-page deterministic V1 protocol fixture before application code
  loads. It implements only WebSocket lifecycle plus fixed login, room-list,
  friend-list, and avatar responses; it grants no server-side security claim.
- Require successful navigation to the production chat shell, visible account
  and friend directory state, and absence of the submitted password from
  `localStorage` and `sessionStorage`.
- Toggle the authenticated browser offline, require the global offline banner
  and retained directory UI, restore networking, and require exactly one new
  `LOGIN_REQ` in addition to the original login.
- Add mandatory `authenticatedClientShell`, `credentialsRemainMemoryOnly`, and
  `authenticatedOfflineRecovery` checks to all six host records.

## Consequences

The branded matrix now proves a deeper client-side V1 journey consistently
without depending on a mutable shared account or production backend. Server
authentication, authorization, TLS routing, protocol compatibility, and real
data remain covered by their own integration/release gates. Media and slow
network behavior remain open.

## Verification

- `npm run test:browser` from `WebClient/`
- `python3 Tests/web_browser_host_evidence_test.py`
- `python3 Tests/web_browser_matrix_completion_test.py`
