# ADR-0410: Session-Bound Web Push HTTP Credentials

- Status: Accepted
- Date: 2026-08-17
- Owners: project maintainers
- Related milestone: M6
- Extends: ADR-0409

## Context

ADR-0409 requires the Web Push subscription API to authenticate a server-bound
account/device session and to reject ambient browser credentials. The Web
client's detached HTTP adapter therefore needs a short-lived bearer/CSRF pair,
but the system has no independent HTTP credential issuer. Reusing the WSS resume
proof would merge two security purposes, expand the consequence of disclosure,
and make independent revocation and auditing impossible. Supplying credentials
in every authentication response would expose them to clients that never enable
Web Push.

## Decision

- Reserve V2 capability 8 and message types 136/137 for on-demand Web Push HTTP
  credential issuance. The command payload is empty. Account, device, and
  session authority comes only from the authenticated WSS connection.
- The gateway may negotiate capability 8 only under the future exact-default-off
  Web Push activation gate and only when a Web client explicitly requests it.
  Windows and ordinary Web builds request nothing and remain compatible.
- Issue an independent random bearer and CSRF token as unpadded Base64URL ASCII.
  They are never derived from, interchangeable with, or accepted as the WSS
  resume proof. The response contains those two values and their absolute
  expiry only.
- Bind one active HTTP credential to one active device session. Issuance
  atomically validates the authenticated account/device/session tuple and
  replaces the session's prior credential. Session or account/device revocation,
  expiry, explicit replacement, or feature rollback makes it unusable.
- Store only SHA-256 token verifiers in PostgreSQL. Plaintext exists only in
  owned, zeroable application/transport buffers and page memory; it is excluded
  from logs, metrics, exception messages, durable Web storage, and diagnostic
  evidence. Authentication returns a fixed decision and server-bound actor,
  never the presented token or verifier.
- Keep issuance and HTTP authentication behind application ports. PostgreSQL is
  durable authority; the WSS gateway and HTTP handler adapt those ports without
  sharing transport session objects or introducing a network service boundary.
- Use an initially reviewed ten-minute lifetime, with a database-enforced upper
  bound of one hour. The client obtains a fresh lease for every subscription
  mutation and does not refresh or persist one in the background.

## Consequences

Web Push opt-in requires an authenticated V2 WSS connection at issuance time.
Losing that connection does not extend the credential lifetime, while revoking
the underlying session invalidates HTTP access. Replacement permits simple
single-current-credential reasoning but concurrent tabs using the same device
session can invalidate each other's short-lived lease; clients already acquire
one immediately before each mutation and surface fixed retry behavior.

This adds two permanent wire identities and a secret-verifier table. It does
not activate Web Push, install a gateway handler, expose an HTTP route, or add
the capability to a default client. A future distributed credential cache may
optimize lookup only as rebuildable state; PostgreSQL remains authoritative.

## Verification and rollback

- Protocol tests lock capability 8, types 136/137, envelope kinds, and the
  secret/expiry wire shape in generated Java, TypeScript, and C++ bindings.
- Application tests prove exact-default-off behavior, authenticated-actor
  binding, fixed unavailable-session mapping, redacted rendering, and owned
  secret cleanup.
- Migration V054 and the detached PostgreSQL adapter now store only unique
  bearer and CSRF SHA-256 verifiers, cap lifetime at one hour, and bind one row
  by cascading foreign key to the device session. Real-database integration
  proves active-session issuance, session-lifetime clipping, immediate
  replacement, expiry, CSRF mismatch, and session/account/device revocation.
  The gateway handler is still uncomposed.
- The detached V2 handler accepts only an authenticated Web capability-8
  connection, an empty type-136 command, and no client operation identity. It
  binds the application actor from connection state, moves issuance to a
  bounded executor, closes plaintext ownership before scheduling the response,
  and emits only fixed errors/events. A separate adapter maps the application
  authenticator to the HTTP handler's fixed actor/decision contract without
  retaining tokens. Neither is installed by runtime composition.
- Rollback removes capability negotiation and handler composition first. The
  additive registry identities and migration remain; credential rows expire or
  can be erased without touching chat or subscription truth.
