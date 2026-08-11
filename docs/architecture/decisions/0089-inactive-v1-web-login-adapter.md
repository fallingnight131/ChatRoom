# ADR-0089: Inactive V1 Web Login Adapter

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The Java gateway needs a V1 boundary while Web and Windows clients migrate
incrementally, but exposing a partial route would let login appear successful
before room, contact, message, and file commands exist. V1 clients also provide
no durable device identifier, whereas Java session issuance requires one.

## Decision

- Add a Netty V1 Web login handler and keep it out of every listener pipeline
  until a separately reviewed end-to-end compatibility slice is complete.
- Accept one login attempt per connection. Decode only bounded text JSON, apply
  the shared process/account/direct-peer admission policy, and move password work
  to the existing fixed authentication executor.
- Bind the established V1 numeric identity plus canonical account/device/session
  UUIDs to a server-owned channel attribute. Client fields never replace it.
- Use the fixed client-device alias `legacy-v1-web` for this compatibility
  generation. This avoids an unbounded device row per reconnect; all old Web
  sessions for an account intentionally share one revocation unit. A future
  optional stable device field requires a versioned compatibility decision.
- Return the same bounded `LOGIN_RSP` rejection for malformed input, admission
  denial, bad credentials, saturation, and internal failure, then close. Do not
  expose retry internals, database errors, UUIDs, sessions, or resume secrets.
- Reuse fixed-cardinality authentication metrics. Diagnostics and limiter cleanup
  must never change a successful or rejected protocol outcome.
- Keep real PostgreSQL/cryptography/use-case composition in one detached module
  so listener wiring cannot accidentally substitute an unrestricted V2 login
  service or a test-only identity projection.

## Consequences

The login boundary can now be tested through Netty without making Java a partial
V1 product server. Rejected clients reconnect rather than retrying on the same
socket. The fixed device alias is intentionally less expressive than V2 devices
and must not be reused for native V2 clients.

Before routing is enabled, the gateway still needs an exact WSS endpoint policy,
single-account connection replacement, heartbeat/lifecycle behavior, and every
post-login command required by the supported V1 client path (or an explicit
client capability gate that prevents entry into unsupported screens).

## Verification

Embedded-channel tests prove off-loop dispatch, direct-peer admission, success
binding, later-frame forwarding, generic malformed/denied/saturated/rejected/
failed output, close behavior, and suppression of late success after a concurrent
second attempt. The full Java workspace gate remains required.
The disposable PostgreSQL gate additionally proves the real composition accepts
a mapped imported account, stores only the fixed V1 device alias plus hashed
session proof, and rejects a password-valid unmapped V2-native account without
issuing another session.

## Rollback

Remove the uninstalled handler, attribute, and tests. No listener, external
protocol route, database schema, imported identity, or current client changes.
