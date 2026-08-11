# ADR-0067: V2 Authenticated Messaging Dispatch

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

V2 had permanent submit/accept/history/page wire types and a PostgreSQL adapter,
but no gateway path joined them. The first reachable slice must not run blocking
database work on Netty event loops, accept client-asserted identity, reorder one
connection's commands, or expose storage/authorization detail.

The wire contract also reserved `content_type` without assigning a first
concrete value. Dispatching arbitrary positive values would make later schema
interpretation ambiguous.

## Decision

- Permanently allocate content type 1 as nonempty UTF-8 text. Its encoded body is
  limited to 65,536 bytes. Unknown, empty, oversized, or invalid UTF-8 content is
  rejected before reaching the application port.
- Install one authenticated messaging handler after authentication in the
  post-upgrade pipeline. It accepts only command envelopes for message types 100
  and 102.
- Derive account and device only from server-bound authenticated connection
  state. The command supplies conversation UUID, cursor/content, and the
  envelope idempotency key, never authority.
- Offload PostgreSQL submission/history work to the gateway-owned bounded worker
  executor. Keep at most 16 queued commands per connection and execute one at a
  time so accepted/history responses preserve that connection's input order.
- Map membership denial and idempotency conflict to their fixed opaque protocol
  errors. Map saturation to retryable `RATE_LIMITED` and unexpected application
  failures to retryable `INTERNAL_ERROR`; none of these expected outcomes closes
  an authenticated connection.
- Build acceptance identity/sequence/time from the durable application result
  and build history from bounded application projections. Never serialize SQL
  rows or client timestamps as authority.
- Wire the same PostgreSQL adapter as both submission and history port in
  `GatewayMain`. This enables the additive pre-cutover V2 path but does not move
  supported Web/Windows traffic from V1.

## Consequences

- A negotiated and authenticated V2 connection can durably submit text and read
  a forward sequence page through the real PostgreSQL boundary.
- Duplicate acceptance remains a successful response with the same stable
  message identity; delivery fan-out, read state, and reconnect orchestration are
  separate slices.
- Messaging and authentication currently share one bounded gateway worker pool.
  This is safe for the inactive pre-cutover slice but must be separated and sized
  from observed workload before product traffic or load claims.
- Content types are now a permanent additive registry. Future rich text,
  attachments, replies, or events require new values and compatibility tests;
  value 1 cannot be reinterpreted.

## Verification

Embedded-channel tests cover server-bound identity, durable acceptance mapping,
ordered history projection, authorization/conflict errors, invalid text,
saturation, connection survival, and serialized offloaded work. The normal
three-language golden binding gate and disposable PostgreSQL gate cover the wire
and durable adapter/composition boundaries.

## Rollback

Remove the gateway handler from post-upgrade composition while retaining the
permanent wire and content-type values as reserved. No V1 route or data model is
changed by this slice.
