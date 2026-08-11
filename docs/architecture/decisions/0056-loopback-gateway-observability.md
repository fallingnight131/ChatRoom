# ADR-0056: Loopback Gateway Health and Metrics Surface

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

Authentication counters existed only as an in-memory snapshot. A deployment
cannot make readiness decisions, alert on overload, or scrape those values
without a concrete operational boundary. Exposing an unrestricted management
listener before the product listener is hardened would create a new attack
surface and could leak high-cardinality identity data.

## Decision

- Add a separate JDK HTTP management server that must bind a resolved loopback
  address. Wildcard and non-loopback binds are rejected.
- Expose exact GET-only paths: `/health/live`, `/health/ready`, and `/metrics`.
  Reject suffix paths, queries, and non-GET methods. Responses are `no-store`
  plain text with content sniffing disabled.
- Start readiness as false and require the future runtime composition root to
  mark it ready only after required dependencies and the product listener are
  usable. Liveness means only that the admin process is serving.
- Use a fixed 1..4 worker pool, daemon worker ownership, a bounded listen backlog,
  and bounded shutdown wait. This server is for a local node agent or sidecar;
  it is not a public API.
- Render Prometheus text directly from the immutable authentication snapshot.
  Export only fixed outcome labels, enum-backed denial dimensions, predefined
  duration buckets, counts, sums, and maximum duration. Never export account,
  peer, request, session, exception, or credential values.
- Do not start the admin or product listener from the placeholder `GatewayMain`
  yet. Runtime configuration, trusted-proxy enforcement, TLS/WSS, origin policy,
  and full dependency wiring remain prerequisites.

## Consequences

- Deployment integration has a concrete, low-cardinality scrape and probe
  contract without committing to a metrics vendor library.
- Loopback binding requires a node-local scraper or sidecar proxy. A future
  remote management channel needs its own authentication, network policy, and
  ADR; changing this server to wildcard binding is forbidden.
- The metrics endpoint does not make current counters a capacity benchmark or
  prove production alert thresholds.

## Verification

Tests prove wildcard and excessive worker configurations fail, readiness starts
at 503 and changes explicitly, liveness and metrics return 200, security/cache
headers are present, POST returns 405, suffix paths return 404, duration buckets
are cumulative, and representative account/IP strings are absent.

## Rollback

Remove the unused operations classes and tests. No runtime starts the management
server yet, so rollback changes no network or product behavior.
