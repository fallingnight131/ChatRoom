# ADR-0075: Web V2 WebSocket Lifecycle Boundary

- Status: Accepted
- Extended by: ADR-0080
- Date: 2026-08-12
- Related milestone: M3

## Context

ADR-0074 made V2 protocol phases and payloads testable without networking. The
browser still needs one owner for WebSocket construction, exact endpoint and
subprotocol selection, connection-phase deadlines, binary delivery, teardown,
and reconnect policy. Reusing the large V1 socket singleton would mix two
protocol generations and make rollback or staged rollout unsafe.

## Decision

- Add a separate, currently unconnected TypeScript transport for the exact
  `wss://<authority>/v2/web` endpoint and single `chat.v2` subprotocol. Reject
  credentials, queries, fragments, insecure WebSockets, and other paths in its
  configuration.
- Create a fresh ADR-0074 protocol client for every opened connection. Send
  `ClientHello` only after the browser confirms the selected subprotocol and
  request `arraybuffer` delivery; reject all other inbound data.
- Bound socket connect, V2 hello, and fresh-authentication phases with positive,
  cancellable deadlines. Protocol failures close with safe fixed reasons and do
  not expose parser or exception details.
- On unexpected close, clear the protocol client and its in-memory session proof,
  then use capped exponential full jitter. Reset the attempt counter only after
  a session is established, so rapidly flapping connections continue to back
  off. Explicit stop cancels every timer and does not reconnect.
- Do not replay passwords, authenticated commands, or pending request state.
  Session resumption, browser connectivity signals, liveness, application
  orchestration, and V1-to-V2 traffic selection remain separate decisions.
- Expose cancellable, exception-isolated state/protocol/failure observers so an
  application service can consume transport results without coupling transport
  ownership to Vue or Pinia.

## Consequences

V2 network lifecycle is isolated from Vue, Pinia, and the V1 singleton and can be
enabled or removed without changing the current user path. Reconnect produces a
negotiated but unauthenticated connection until later resume/login orchestration
is implemented. Browsers cannot originate WebSocket ping control frames, so the
gateway idle policy still needs an explicit browser liveness decision before
cutover.

## Verification

Deterministic tests use injected sockets, timers, randomness, and protocol-client
factories. They verify the exact route/subprotocol, binary hello/authenticated
command flow, state events, deadline closure, subprotocol and non-binary failure,
per-connection cleanup, capped-jitter retry, synchronous constructor failure,
and explicit cancellation. The complete Web test/build gate remains required.

## Rollback

Remove the unreferenced transport, tests, and this decision. ADR-0074, generated
bindings, the Java gateway, and the live V1 Web path remain unchanged.
