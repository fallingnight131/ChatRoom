# ADR-0215: Make the Supported V1 Web Transport Offline-Aware

- Status: Accepted
- Date: 2026-08-13
- Owners: Web client and quality
- Related milestone: M4
- Extends: ADR-0033 client cache behavior and ADR-0111 endpoint policy

## Context

The supported V1 Web transport created a WebSocket even when the browser was
offline and then consumed a fixed ten-attempt retry budget. The login screen
reported only a generic connection error, while an authenticated user viewing a
conversation had no persistent offline status. V2 already pauses on browser
network signals, but the production V1 path did not.

## Decision

- Treat `navigator.onLine === false` only as a local offline signal, never as
  proof that the gateway is reachable when true.
- While offline, create no new V1 socket, cancel reconnect and heartbeat timers,
  close the current socket, and emit explicit offline state.
- On one offline-to-online transition, emit online state and immediately create
  at most one replacement socket when a pre-loss connection makes automatic
  reconnect authorized. A new login intent created while already offline is
  never authorized for automatic recovery and requires explicit retry.
- Explicit disconnect, logout, and forced-offline paths continue to disable
  automatic reconnect and cannot be revived by a later online event.
- Announce actionable offline/recovery text on login and show a global polite
  offline banner in chat while cached content remains visible.

## Consequences

Offline periods no longer burn retries or create connection noise, and recovery
does not wait for the five-second timer. Browser online state remains advisory;
ordinary connection failures still follow bounded retry behavior. This changes
no wire message or server authority and can be rolled back independently.

## Verification

- `node --test WebClient/tests/v1BrowserNetworkLifecycle.test.mjs`
- `npm test` and `npm run build` from `WebClient/`
- Chromium/Firefox browser compatibility gate
