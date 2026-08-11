# ADR-0043: V2 Gateway Authenticated Connection State

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

V2 negotiation previously forwarded every post-hello envelope without binding
an authenticated identity to the connection. Accepting an envelope
`session_id` as authority would let a client claim another session, while
running Argon2id verification on a Netty event loop would stall unrelated
connections.

Session resume/rotation, distributed limits, listener hardening, and production
traffic are not ready. This slice must therefore establish the fresh-login
boundary without pretending the remaining controls exist.

## Decision

- Store validated Web/Windows client metadata as negotiated, untrusted channel
  state after `ClientHello`.
- Require exactly one fresh `Authenticate` command while unauthenticated.
  Explicitly return the same generic authentication rejection for
  `ResumeSession` until resume verification and token rotation are implemented.
- Parse and bound secret-bearing payloads before application dispatch. Copy the
  password into an owned application command, clear the temporary mutable copy,
  and close the command after the use case returns.
- Invoke the transport-independent `AuthenticationUseCase` through an injected
  executor. The Netty event loop owns connection state and outbound writes but
  never performs password hashing or database work.
- Move the connection through `EXPECTING_AUTHENTICATION`, `AUTHENTICATING`,
  `AUTHENTICATED`, or terminal state. A second command during authentication is
  an invalid-state error followed by close.
- On success, bind the server-issued account, device, and session UUIDs to a
  typed channel attribute. Downstream authorization must use this server state;
  the envelope `session_id` is only checked for equality and never grants
  identity.
- Return the raw resume token once in `SessionEstablished`, then clear its
  owned memory. Record only non-secret accepted/rejected/failed outcomes and the
  internal legacy-credential-upgrade-pending flag.
- Normalize unexpected failures to a fixed retryable internal protocol error.
  Do not expose exceptions, account state, hashes, database details, or tokens.

## Consequences

- A negotiated V2 channel can now establish a server-authoritative fresh-login
  identity without blocking its event loop.
- This handler remains additive and is not installed on a network listener.
  Bounded worker pools, admission/rate limits, authentication timeout, WSS and
  origin policy, outbound WebSocket encoding, and session resume are still
  required before traffic enablement.
- Downstream application handlers receive only envelopes whose session ID
  agrees with the server-bound identity, but each use case must still enforce
  authorization against durable server state.

## Verification

Embedded-channel tests cover success and one-time token clearing, server-side
identity binding, downstream forwarding, generic credential/resume rejection,
malformed credentials, session spoofing, and normalized application failure.
Handshake tests verify that negotiated client metadata is retained. No listener
or capacity claim is introduced.

## Rollback

Remove the unused handlers and channel attributes. V1 remains authoritative and
no production traffic, persistent row, or client contract depends on this
additive gateway slice.
