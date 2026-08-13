# ADR-0060: Gateway Runtime Configuration Boundary

- Status: Accepted
- Extended by: [`ADR-0346`](0346-bounded-gateway-connection-drain.md)
- Date: 2026-08-11
- Related milestone: M3

## Context

The gateway security components had constructor configuration but no single
deployment contract. Reading ad hoc environment values inside handlers would
spread parsing, permit unsafe fallbacks, risk logging secrets, and make the
event-loop, authentication, timeout, proxy, Host, Origin, TLS, and PostgreSQL
settings impossible to validate before binding a port.

## Decision

- Keep the composition root in the independently deployable `im-gateway` module
  established by ADR-0032. Do not create a second network service solely for
  dependency wiring.
- Add one immutable `GatewayRuntimeConfig` built from an injected environment
  map. Parse and validate all fields before TLS or database initialization and
  before either listener binds.
- Require readable, distinct certificate/private-key files; exact Host and Web
  Origin lists; a PostgreSQL JDBC URL, user, and non-empty password. TLS private
  key password remains optional for protected unencrypted PEM deployments.
- Accept only numeric listener/admin IP literals to avoid startup DNS ambiguity.
  Default the product listener to `127.0.0.1:9443` and require the admin address
  to remain loopback. Ports and worker/queue/timeout/limiter values have explicit
  ranges.
- Default to direct-peer mode. Enable trusted forwarding only when one or more
  explicit proxy CIDRs are configured.
- Keep database/TLS passwords private to the runtime package and do not
  implement value-bearing `toString`, record equality, logging, or error text.
  All validation errors are fixed and omit environment values and paths.
- Treat numeric defaults as conservative development/deployment starting points,
  not measured production capacity. Operators must tune them using stored load
  evidence.

## Consequences

- The future listener can be assembled from one validated object, making
  fail-before-bind behavior testable and reducing configuration drift.
- Certificate/key existence is validated, but cryptographic parsing and key/
  certificate matching remain listener-start checks.
- PostgreSQL transport/TLS mode and pool ownership must be defined by the next
  composition slice; this configuration alone does not connect or listen.

## Verification

Tests cover safe defaults, policy construction, trusted CIDRs, numeric listener
addresses, loopback-only admin binding, required secrets, PostgreSQL URL shape,
unreadable/aliased TLS files, malformed lists, bounded workers, and proof that
object text does not contain configured passwords.

## Rollback

Remove the unused runtime configuration class/tests and deployment reference.
`GatewayMain` still does not bind, so rollback changes no active route.
