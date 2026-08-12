# ADR-0216: Require Offline Login Behavior in Branded Browser Evidence

- Status: Accepted
- Date: 2026-08-13
- Owners: Web client and quality
- Related milestone: M4
- Supersedes: ADR-0214 evidence schema 2
- Extends: ADR-0215

## Context

Unit tests prove the V1 transport's state machine, but browser network events,
`navigator.onLine`, Vue event delivery, and WebSocket construction can differ by
browser. Schema 2 did not cover that integration.

## Decision

- Advance branded-browser host evidence to schema 3.
- Load the exact candidate, switch the browser context offline, submit validly
  shaped login credentials, and require zero WebSocket creation plus an
  assertively announced offline message.
- Restore the context online and require an announced explicit-retry state with
  no automatic socket or retained login attempt from the unauthenticated page.
- Add mandatory `offlineLoginPaused` and `recoveryStateAnnounced` checks to all
  six host records. Reject older records at verification and matrix closure.

## Consequences

The branded matrix now covers login accessibility and offline browser
integration. It still does not prove authenticated cached-conversation display,
session resume, slow networks, or media decoding; those require separate deeper
fixtures and evidence.

## Verification

- `python3 Tests/web_browser_host_evidence_test.py`
- `python3 Tests/web_browser_matrix_completion_test.py`
- `npm run test:browser` from `WebClient/`
