# ADR-0072: V2 Conversation Directory Dispatch

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The directory application/SQL boundary and types 110/111 are independently
verified. Connecting them through a separate asynchronous Netty handler would
allow one connection's directory read to race its preceding message append,
violating the existing connection-local command ordering expectation.

## Decision

- Extend the authenticated conversation/message command dispatcher to accept
  type 110 and execute it in the same one-in-flight, 16-pending per-connection
  queue used for submit and history commands.
- Derive the query account only from server-bound authenticated connection
  state. The payload carries only a composite browsing cursor and limit.
- Execute the PostgreSQL directory port on the isolated bounded messaging worker
  pool, never a Netty event loop.
- Map application summaries to bounded type 111 records and validate the complete
  outbound page before writing it. Unexpected persistence/projection failures use
  the existing retryable safe internal error without closing the connection.
- Compose the PostgreSQL directory adapter in `GatewayMain` and add a fixed
  `directory_page` outcome to the existing identity-free messaging telemetry.

## Consequences

- An authenticated pre-cutover V2 connection can discover its active canonical
  conversations and then request sequence history without out-of-band UUIDs.
- Submit, history, and directory work from one connection preserve input order;
  no global or cross-connection ordering is promised.
- Conversation creation, membership mutation, delivery fan-out, and supported-
  client adoption remain separate cutover requirements.

## Verification

Embedded-channel tests prove the account comes only from authenticated state,
the bounded application query receives the requested limit, the response carries
canonical summary/cursor fields, and the fixed directory outcome increments.
Full Java and disposable PostgreSQL gates verify composition and resource
lifecycle with the real adapter.

## Rollback

Remove type 110 from gateway dispatch and the adapter from runtime composition.
Keep permanent wire values and additive V004 storage reserved while V1 remains
authoritative.
