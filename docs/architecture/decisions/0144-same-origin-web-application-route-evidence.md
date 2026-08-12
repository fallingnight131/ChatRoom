# ADR-0144: Same-Origin Web Application Route Evidence

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

A Web release can serve correct immutable static bytes while `/api/` or `/ws`
is absent, routed to the wrong upstream, redirected, or terminated with an
invalid WebSocket handshake. Static observation from ADR-0114 and rollback
evidence from ADR-0142 therefore cannot alone prove that the chat application is
reachable. ADR-0143 provides a credential-free V1 HTTP routing identity.

## Decision

- Add a separate provider-neutral application-route observation. Keep it
  distinct from static-release observation so operators can identify which
  release boundary failed.
- Require one credential-free HTTPS origin with normal certificate validation
  and TLS 1.2 or later. Do not support insecure-skip, redirects, credentials,
  cross-origin paths, queries, fragments, dot segments, or protocol-relative
  paths.
- Fetch exact `GET /api/health` and require the ADR-0143 status, canonical body,
  content type, no-store and nosniff headers. Reject compression, cookies, and
  wildcard CORS.
- Perform an RFC 6455 upgrade at exact `/ws` using a fresh random 16-byte
  challenge. Require exact HTTP/1.1 status 101, a valid computed
  `Sec-WebSocket-Accept`, Upgrade/Connection semantics, bounded ASCII headers,
  and no redirect, cookie, CORS, or unexpected response bytes.
- Permit reviewed exact alternative same-origin paths for deployments built
  with a corresponding WebSocket path, but keep `/api/health` and `/ws` as the
  defaults.
- Emit a closed, credential-free, write-once observation with origin, exact
  paths, protocol/status identities, and UTC observation time.

## Consequences

The release gate can distinguish valid static delivery from a valid application
route and rejects a superficial 404/redirect success. The handshake opens no
authenticated session and sends no chat payload. It proves route, TLS, and
upgrade behavior only; it does not prove login, database availability, message
delivery, file authorization, load capacity, or continuing availability.

## Migration and Rollback

Deploy the ADR-0143 server before requiring this observation. A reverse proxy
must map the exact public paths to the V1 HTTP and WebSocket listeners. Removing
the tool changes no traffic, but release promotion must remain stopped when the
route evidence is absent or invalid.

## Verification

- a temporary trusted TLS origin returns the exact V1 health response and a
  nonce-bound WebSocket 101 handshake;
- wrong health identity, redirects, wrong challenge response, HTTP, unsafe
  paths, untrusted TLS, unknown evidence fields, and evidence overwrite fail;
- CI creates and deletes its one-day localhost certificate/key in a temporary
  directory.
