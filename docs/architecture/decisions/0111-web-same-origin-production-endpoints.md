# ADR-0111: Web Same-Origin Production Endpoints

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The V1 Web client previously selected a hard-coded production host and also
accepted `serverHost`, `serverPort`, and `wsPath` values left in `localStorage`.
Those values were not exposed in the current UI, but an old build or injected
script could redirect a later login, reconnect, or file authorization to an
unrelated origin. This also prevented a useful production Content Security
Policy from limiting `connect-src` to the application origin.

The application is developed on macOS and still needs a low-friction loopback
path to the Qt V1 server. Public Web delivery, however, needs one HTTPS reverse-
proxy boundary for static assets, `/ws`, and `/api/*`.

## Decision

- A non-loopback Web page is a production page and must use HTTPS. Its V1
  WebSocket endpoint is `wss://<page-authority>/ws` by default, and its V1 file
  endpoints are resolved beneath `/api/` on the exact page origin.
- `VITE_CHAT_V1_WS_PATH` may replace `/ws` with a bounded same-origin absolute
  path. Schemes, authorities, query strings, fragments, backslashes, dot
  segments, and protocol-relative paths are rejected.
- Legacy persisted server overrides are removed and no longer influence login.
  Production users cannot select an arbitrary chat server from browser storage.
- Loopback pages (`localhost`, `127.0.0.1`, or `[::1]`) retain direct V1
  development at port 9528. Their `/api/` requests use the Vite same-origin
  proxy during development. This is development-host behavior, not a product
  endpoint or an HTTP production exception.
- The WebSocket compatibility service accepts a pre-resolved `ws:`/`wss:` URL
  without credentials or a fragment. The user/application boundary owns the
  stronger production same-origin decision.
- Tokenized upload/download URL construction rejects paths outside same-origin
  `/api/`. The server-advertised HTTP host/port remains only a non-browser
  fallback; a browser origin takes precedence.
- The default-off V2 preview retains its separately guarded exact WSS setting.
  Enabling a cross-origin preview later requires an explicit CSP allowlist or a
  same-origin gateway route; it does not weaken the default V1 policy.

## Consequences

Production can deploy a narrow `connect-src 'self'` policy once the reverse
proxy and security headers are verified. Hosting the static application without
same-origin `/ws` and `/api/` routing now fails visibly instead of silently
falling back to a different server. Existing deployments on the former default
domain behave the same when their reverse proxy owns those paths.

This does not itself apply CSP/HSTS headers, prove a browser support matrix, or
deploy a release. Those remain separate M4 gates. File bearer tokens remain in
URLs for V1 compatibility and should continue to be short-lived and redacted
from logs.

## Migration and Rollback

Before deploying this Web build, route `/ws` to the V1 WebSocket listener and
`/api/` to the V1 HTTP listener on the same HTTPS authority. No server protocol
or durable data changes are required. Rollback deploys the previous immutable
Web version; removed legacy browser overrides are deliberately not restored.

## Verification

- TypeScript tests cover HTTPS same-origin production resolution, loopback
  development, invalid paths, insecure production pages, and denied storage;
- JavaScript compatibility tests prove upload/download URLs prefer the page
  origin and reject API-boundary escapes;
- the complete Web Node test suite and production Vite build must pass.
