# ADR-0001: Incremental Evolution to a Java Messaging Platform

- Status: Accepted
- Date: 2026-07-10
- Owners: project maintainers
- Related milestone: M0-M5

## Context

The current Qt/C++ server already supports TCP, WebSocket, HTTP, rooms, direct
messages, files, SQLite, and COS. Its active implementation has diverged from the
legacy design document and concentrates transport, business routing, persistence,
and file concerns in a small number of large classes.

A direct source-to-source Java rewrite would preserve the current bottlenecks and
create a long period with no releasable product. The project needs stronger
message semantics and module boundaries before it needs many services.

## Decision

Evolve through compatible vertical slices:

1. stabilize and measure V1;
2. add reliable message identity, ordering, synchronization, and direct object
   storage;
3. build a Java modular monolith and a Netty IM gateway;
4. expose V1 JSON through a boundary adapter and introduce a generated V2
   protocol;
5. migrate one domain slice at a time to PostgreSQL-backed Java modules;
6. add Redis routing and a durable event broker only when multiple runtime
   instances require them.

The Java target uses Spring Boot for HTTP/application bootstrap and Netty for the
long-lived messaging gateway. Domain modules communicate in-process initially.

## Alternatives Considered

- Keep the C++ server indefinitely: viable for performance, but it does not by
  itself address the current structure, delivery semantics, or desired Java
  ecosystem.
- Rewrite everything and switch once: rejected due to compatibility, migration,
  regression, and rollback risk.
- Begin with many microservices: rejected because current load and team topology
  do not justify the operational cost.

## Consequences

- V1 compatibility code temporarily coexists with V2.
- Data migration and protocol compatibility become first-class test areas.
- The project can release improvements before Java migration finishes.
- The Java codebase must enforce module boundaries to avoid recreating the C++
  monolith in another language.

## Migration and Rollback

Keep one authoritative database during each cutover step. Back up and validate
before one-way migration. Route a vertical slice to Java only after shadow or
integration verification. Preserve the previous server artifact and documented
database restore point for the rollback window.

Do not use uncontrolled bidirectional dual writes.

## Verification

- V1-old-client/new-server compatibility tests;
- V2 generated-schema compatibility tests;
- duplicate/retry/reconnect tests;
- migration row counts and semantic checks;
- latency and resource comparison against the recorded V1 baseline;
- rollback rehearsal before production cutover.

