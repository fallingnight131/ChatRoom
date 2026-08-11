# ADR-0037: V2 Gateway Handshake State Machine

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The V2 frame boundary can decode valid envelopes and the control registry defines
negotiation payloads, but a connection still needs a deterministic pre-auth
state. Dispatching arbitrary messages before version/platform negotiation would
make authentication and error behavior ambiguous and expose more work to
unauthenticated peers.

## Decision

- Each V2 connection starts in `EXPECTING_HELLO`; `ClientHello` command must be
  its first application envelope.
- Bound the serialized ClientHello to 512 bytes before parsing, then apply the
  shared structural and version-overlap policies.
- On success, move once to `NEGOTIATED` and return a `ServerHello` containing
  server-selected protocol version, server-generated diagnostic connection ID,
  server time, and maximum frame size. These fields do not authenticate a user.
- Wrong first type/kind, malformed or oversized payload, unsupported version,
  and repeated hello produce fixed safe `ProtocolError` payloads and close the
  connection after the error write. Exception text is never reflected.
- After negotiation, pass non-hello envelopes to the next application adapter.
  That adapter must still enforce registry direction, authentication state,
  authorization, and payload policy.
- Do not bind a listener until timeout/rate-limit handling, frame-error close
  mapping, observability, authentication dispatch, and an outbound encoder are
  installed and tested as one pipeline.

## Consequences

- Unauthenticated state allocation and parsing are bounded and deterministic.
- Clients receive stable safe errors for negotiation failures.
- A successful hello is not a session and cannot be used as authorization.
- V1 transport remains the only active product route.

## Verification

Embedded-channel tests compose binary frame decoding with the handshake handler
and cover success, safe authority fields, next-handler forwarding, wrong first
frame, malformed/oversized payload, unsupported versions, repeated hello, close
behavior, and non-retryable safe errors.

## Rollback

Remove the unused handler and tests. No listener, persistent state, client route,
or V1 behavior changes.
