# ADR-0058: WebSocket Endpoint, Origin, and Platform Binding

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

Web browsers automatically send an `Origin` during WebSocket upgrade, while the
Windows native client does not need browser-origin semantics. A shared endpoint
that merely permits a missing Origin would let a browser-facing route lose CSRF
protection, and trusting `ClientHello.platform` alone would let a peer claim the
less restrictive client type after upgrade.

## Decision

- Reserve exact upgrade paths `/v2/web` and `/v2/windows`. Reject queries,
  unsupported paths, non-GET requests, malformed decoder state, missing
  `Upgrade: websocket`, or a `Connection` header without the `upgrade` token.
- Require exactly one allowed Origin on `/v2/web`. Configuration accepts 1..32
  unique HTTPS authority origins only; normalize host case and the default 443
  port, and reject user info, paths, query, fragment, whitespace, insecure HTTP,
  and values over 512 characters.
- Require Origin to be absent on `/v2/windows`. Standards-compliant browsers
  send Origin and therefore cannot select the Windows endpoint to bypass the Web
  allowlist.
- Freeze the endpoint's expected Web/Windows platform in server-side channel
  state before WebSocket negotiation. Reject a second upgrade attempt.
- After binary negotiation, require `ClientHello.platform` to equal the frozen
  endpoint platform. Mismatch returns a fixed non-retryable invalid-payload
  protocol error and closes; it never changes the endpoint authority.
- The future listener must order HTTP aggregation, trusted-proxy resolution,
  endpoint/origin policy, WebSocket upgrade, and then the V2 frame/application
  pipeline. The policy is implemented but no listener is enabled yet.

## Consequences

- Web Origin protection and Windows native access are explicit rather than
  inferred from an untrusted application message.
- Deployment must enumerate every supported Web release origin. Adding an origin
  is security configuration and must be reviewed with Web deployment/CSP state.
- HTTPS Origin validation does not replace WSS/TLS, Host validation, reverse
  proxy sanitization, or authentication after upgrade.
- Existing V1 routes and inactive V2 wire encodings are unchanged.

## Verification

Tests cover normalized HTTPS origins, exact Web/Windows successes, missing/
hostile/multiple-origin rejection, insecure or path-bearing configuration,
wrong method, missing upgrade token, query paths, repeated upgrade, fixed generic
HTTP rejection, reference ownership, and `ClientHello` endpoint-platform
mismatch closure.

## Rollback

Remove the unused policy/handler, expected-platform channel state, handshake
comparison, and tests. No listener installs them yet, so rollback changes no
active product route.
