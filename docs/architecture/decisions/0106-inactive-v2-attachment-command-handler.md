# ADR-0106: Inactive V2 Attachment Command Handler

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

ADR-0105 allocates the V2 attachment protocol and the application/PostgreSQL/S3
boundaries already exist, but transport mapping must not let an envelope choose
account or device authority, block a Netty event loop, expose foreign object
existence, or leak a signed URL through failures. Real-provider acceptance is
still incomplete, so this adapter cannot be installed into the runnable
pipeline yet.

## Decision

- Add a dedicated `V2AttachmentHandler` which recognizes only command types
  120, 122, and 124 and passes every other envelope downstream unchanged.
- Derive account and device exclusively from `V2ConnectionAttributes` and build
  application `AttachmentRegistration`/`AttachmentActor` values at the
  transport boundary. Ignore the envelope session string for authority.
- Serialize at most one attachment operation per connection and bound queued
  commands to eight. Execute PostgreSQL/object-store work on an injected bounded
  executor; reject executor or per-connection saturation with a retryable fixed
  error.
- Validate generated payloads before application dispatch. Map registration
  idempotency conflict explicitly, but map missing, foreign, revoked, and
  otherwise unavailable attachment IDs to the same non-enumerating denial.
- Treat a missing uploaded object as retryable incomplete state and checksum/
  size mismatch as non-retryable verification failure. Provider and database
  exceptions become one fixed retryable internal error with no exception text.
- Sort required upload headers for deterministic responses. The signed HTTPS URL
  exists only in the successful authorization response; it is never placed in an
  error, event, log, metric, database command, or messaging event.
- Drop queued work on disconnect and suppress a late dependency result.
- Add fixed-cardinality counters for new/duplicate registration, authorization,
  new/duplicate READY, denial, conflict, invalid input, saturation, and failure.
  They contain no account, device, attachment, conversation, object key, URL, or
  provider labels.
- Keep the handler out of `V2ApplicationPipeline`, `V2GatewayServer`, and
  `GatewayRuntime`. A later activation ADR must first retain the full real-
  provider evidence from ADR-0099/0104 and define worker ownership, Prometheus
  exposure, shutdown order, and Web/Windows rollout behavior.

## Consequences

The critical transport-to-application mapping is independently testable without
cloud access, a listener, or production credentials. Existing V2 messaging and
all V1 paths remain unchanged. The runnable gateway continues to reject the
allocated attachment types as unsupported.

## Migration and Rollback

This is additive and inactive. Rollback removes the handler, telemetry, tests,
and this ADR; permanent protocol numbers and generated bindings remain reserved.
No database, object, message, or runtime configuration changes.

## Verification

Embedded-channel tests cover server-bound identity, registration duplicate,
deterministically ordered grant headers, READY and duplicate completion, opaque
foreign/missing denial, retryable object absence, non-retryable checksum
mismatch, unauthenticated/wrong-kind/malformed input, downstream pass-through,
bounded queue, executor rejection, dependency exception redaction, and late-
result suppression after disconnect. Telemetry tests lock the fixed outcome set.
The full Java `check` is required; no real provider call is made.
