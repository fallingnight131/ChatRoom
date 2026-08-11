# ADR-0046: V2 Connection Phase Deadlines

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

Bounded frames and authentication workers do not stop a client from opening a
connection and withholding `ClientHello`, or negotiating and never completing
authentication. Without phase deadlines, slow or abandoned connections retain
channel and gateway resources indefinitely.

## Decision

- Add a per-channel timeout handler with separately configured positive
  handshake and authentication durations.
- Start the handshake deadline when the active channel receives the handler.
  Successful V2 negotiation emits an internal pipeline event that cancels it
  and starts the authentication deadline from that moment.
- Successful server-side identity binding emits a second internal event that
  cancels authentication expiry. Disconnecting or removing the handler cancels
  every pending scheduled task.
- On expiry, send WebSocket status 1008 (`POLICY_VIOLATION`) with only the fixed
  reason `V2 handshake timeout` or `V2 authentication timeout`, then close.
- Keep deadline values as explicit constructor configuration until listener
  bootstrap, deployment configuration bounds, and operational measurements are
  available. Tests use short virtual durations and do not define product
  defaults.

## Consequences

- A connection cannot occupy the unauthenticated phase indefinitely once this
  handler is installed after handshake and authentication handlers.
- Deadline tasks run on the owning Netty event loop and do no blocking work.
- Idle/heartbeat policy for authenticated connections remains a separate
  transport concern, as do account/IP/gateway abuse windows.

## Verification

Embedded virtual-time tests prove close immediately at each deadline, fixed
1008 reasons, phase transition from handshake to authentication timing, and
cancellation after authentication success.

## Rollback

Remove the unused timeout handler and internal phase events. Do not enable a
listener without an equivalent bounded unauthenticated connection lifetime.
