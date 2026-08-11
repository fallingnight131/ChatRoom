# ADR-0038: V2 Authentication Wire Contract

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

After a successful ClientHello the gateway needs explicit generated payloads for
fresh authentication and session recovery. Reusing protocol errors for wrong
credentials would mix transport failures with identity outcomes, while exposing
different errors for unknown accounts and wrong passwords would enable account
enumeration.

## Decision

- Permanently allocate message types 10 `Authenticate`, 11 `ResumeSession`, 12
  `SessionEstablished`, and 13 `AuthenticationRejected`.
- `Authenticate` carries a bounded username and 1..1024 bytes of valid UTF-8
  password data. Bytes preserve exact client encoding until the identity
  application boundary; no component may log, persist, cache, or echo them.
- `ResumeSession` carries a bounded session ID and exactly 32 bytes of opaque
  resume token. The future repository stores only its SHA-256 digest and rotates
  the raw token when establishing a new connection.
- `SessionEstablished` may return server-issued account/device/session IDs,
  expiry, display name, and the new raw resume token once. The connection must
  then bind the server-side authenticated context; envelope `session_id` alone
  is never trusted.
- Use one generic `REJECTED` result for unknown account, wrong password, disabled
  account, revoked device, invalid token, and expired/revoked session. A distinct
  `RATE_LIMITED` result may include a non-negative retry delay.
- Credential-bearing messages are permitted only after successful negotiation
  over a production WSS listener. TLS, origin policy, logging redaction, rate
  limits, password verification, token generation, and persistence must be
  verified before that listener is enabled.

## Consequences

- Java, Web, and Windows share generated secret-bearing wire fields and bounds.
- Failure behavior avoids account/session enumeration at the wire layer.
- This schema alone performs no authentication and creates no session.

## Verification

- Java tests cover registry kinds, username/password bounds, invalid UTF-8,
  fixed token length, session-ID UTF-8 bounds, and exact Authenticate bytes.
- Generated TypeScript and C++ bindings parse and re-emit the same non-secret
  test-fixture payload.
- Full Java and three-language protocol gates pass.

## Rollback

No listener or supported client uses these additive types. They may be removed
before external release, but numeric IDs must never be reassigned after any
compatibility artifact ships.
