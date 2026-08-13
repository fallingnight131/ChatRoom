# ADR-0064: Gateway Composition Root and Readiness

- Status: Accepted
- Extended by: [`ADR-0346`](0346-bounded-gateway-connection-drain.md)
- Date: 2026-08-12
- Related milestone: M3

## Context

The V2 WSS listener, admin server, PostgreSQL adapter/pool, identity cryptography,
authentication services, admission limits, telemetry, and worker pool existed as
separate verified components. The placeholder `GatewayMain` did not define
dependency validation, readiness transition, failure cleanup, or process
shutdown order, so the gateway could not be exercised as one deployable unit.

## Decision

- Make `GatewayMain` an environment-only composition root. Reject command-line
  values so credentials and endpoints do not drift into process arguments or
  shell history.
- Before any product bind, validate Flyway migration checksums/state, initialize
  the bounded PostgreSQL pool, build the PostgreSQL identity adapter,
  Argon2id/V1-compatible cryptography, authentication/session-resume services,
  process-local admission, telemetry, bounded authentication workers, and TLS
  listener.
- Start the loopback admin server with readiness false, then bind the WSS
  product listener, and only then publish readiness true. Startup failure clears
  readiness and releases every constructed resource.
- On shutdown, clear readiness first, then close product listener/children,
  admin server, authentication workers, and database pool. Make shutdown
  idempotent and let the main thread wait on the product listener close future.
- Log only fixed lifecycle events and exception-class categories at the process
  boundary. Do not include exception messages, configuration, URLs, credentials,
  peer identity, or request data.
- Keep V1 authoritative and do not route product traffic to V2 yet. The runtime
  currently supports negotiation and identity establishment, but durable V2
  conversation/message commands are not implemented.

## Consequences

- The gateway is now independently runnable when all strict environment values,
  migrated PostgreSQL state, and valid TLS material exist. Missing or invalid
  dependencies fail before readiness/product service is advertised.
- Authentication metrics are attached to the exact-path loopback admin server,
  and trusted-proxy/Host/Origin policies are installed by the product listener.
- Starting the binary is an operator action; repository/CI verification does not
  imply traffic cutover, public exposure, capacity, or release readiness.

## Verification

Unit tests verify unready start order, ready transition, reverse close order,
startup-failure cleanup, idempotent close, post-close start denial, listener
termination waiting, and command-line rejection. The disposable PostgreSQL gate
now additionally validates the real composition root against migrated schema,
starts admin and WSS on ephemeral loopback ports, observes HTTP readiness, then
closes and deletes the isolated cluster.

## Rollback

Restore the placeholder `GatewayMain`, remove `GatewayRuntime` and its integration
gate, and retain the independently tested components. No supported client or
production route currently depends on V2.
