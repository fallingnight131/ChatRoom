# ADR-0107: Inactive Web V2 Attachment Upload Coordinator

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

The Web V2 preview can authenticate, discover conversations, synchronize
history, and submit text. ADR-0105 defines the attachment protocol and ADR-0106
implements an inactive server adapter. Web needs the corresponding application
boundary without adding signed URLs or file bytes to Pinia/IndexedDB, coupling
the flow to a Vue view, or enabling a server route before provider acceptance.

Browser WebCrypto currently computes whole-buffer SHA-256 and the S3 adapter
supports only simple PUT. Attempting the database's 10 GiB safety ceiling in
this shape would create unsafe browser memory pressure and is not a supported
product promise.

## Decision

- Extend `V2WebProtocolClient` and `V2WebSocketTransport` with the permanent
  register/authorize/complete commands and their correlated responses. Validate
  UUIDs, basename, canonical MIME, byte/hash bounds, HTTPS grant, expiry, and
  unique lowercase required headers before exposing an event.
- Add a transport- and view-independent TypeScript coordinator. It serializes
  one upload per instance and executes:
  source read and SHA-256, idempotent registration, fresh authorization, direct
  PUT, and checksum-backed completion.
- Require the caller to supply the durable `clientAttachmentId`; the coordinator
  deliberately does not persist it. Existing M2 attachment outbox/reselection
  policy remains the future owner when the preview UI integrates this service.
- Keep source bytes, digest, signed URI, and required headers only in the active
  call. Clear owned byte/hash arrays in `finally`; never publish them in result,
  error, observer, storage, or logs.
- Bound this initial browser whole-buffer path to 100 MiB. Files above it require
  the later reviewed streaming/incremental-hash and restartable multipart design.
  This bound is neither the server schema limit nor a final product capacity.
- Reject a source whose declared size changed after reading. Use empty browser
  MIME as `application/octet-stream`, while the protocol still requires a
  canonical MIME value.
- Require more than five seconds of grant lifetime before PUT. Automatically
  request one replacement for a near-expiry grant and fail retryably if both are
  stale.
- The fetch adapter sends exact signed headers with `credentials: omit`,
  `cache: no-store`, and `redirect: error`. It neither reads nor reports provider
  response bodies or signed URLs. HTTP 429/5xx is retryable; other non-2xx is a
  fixed non-retryable upload rejection.
- Cancel or connection loss aborts PUT and rejects outstanding protocol waits.
  Completion identity must match registration before success is returned.
- Correlate every stage by exact request ID, not response type alone. Cancelling
  marks the protocol request as a bounded tombstone so its late response is
  consumed without reaching observers or occupying active-request capacity;
  retain at most 32 tombstones per connection.
- Keep the coordinator absent from Vue routes and the default-off V2 runtime.
  UI/outbox integration and product activation require real-provider evidence,
  server composition, and a later vertical-slice ADR.

## Consequences

The Web side of the final direct-object path is testable now without a bucket or
enabled gateway handler. The service can later be injected into the preview view
or durable M2 outbox without moving protocol or fetch logic into components.

Whole-buffer hashing temporarily duplicates browser memory. The explicit bound
contains that risk but multipart/resume remains necessary for a modern large-
file experience.

## Migration and Rollback

This is additive and inactive. V1 Web uploads, IndexedDB schema, Pinia state,
routes, build flags, and runtime composition do not change. Rollback removes the
coordinator and transport methods while permanent generated protocol types stay
reserved.

## Verification

Tests cover exact protocol encoding/correlation, unsafe grant rejection,
idempotent duplicate registration/READY, near-expiry refresh, exact direct PUT
headers, transient byte clearing, protocol denial redaction, cancel, disconnect,
source revision mismatch, serialization, HTTP retry classification, omitted
credentials, no redirects/cache, stale-response isolation, bounded cancellation
tombstones, and transport forwarding. Full Web tests and a
production build are required. No real provider request is executed.
