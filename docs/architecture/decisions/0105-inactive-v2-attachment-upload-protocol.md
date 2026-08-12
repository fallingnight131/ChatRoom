# ADR-0105: Inactive V2 Attachment Upload Protocol

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

The Java application and PostgreSQL adapter already define attachment
registration, create-only upload authorization, checksum-backed completion, and
idempotent lifecycle transitions. Web and Windows need one stable V2 contract
before either client or gateway handler can integrate that behavior. The real
object-store acceptance from ADR-0099 remains incomplete, so allocating the
protocol must not activate an unusable or unsafe route.

## Decision

- Permanently allocate message types 120 through 125 for register/registered,
  authorize/authorized, and complete/ready command-response pairs.
- Keep `ownerAccountId` and `ownerDeviceId` out of payloads. A future handler
  must derive both from the authenticated connection and check active
  conversation membership through the existing application boundary.
- Registration carries conversation ID, client attachment idempotency key,
  basename, canonical MIME type, unsigned byte size, and exactly 32 SHA-256
  bytes. It never carries file bytes.
- Registration response returns only stable attachment/conversation/client IDs
  and duplicate status. Authorization is a separate command so retries can
  obtain fresh transient grants without creating a second durable row.
- Authorization response carries an HTTPS signed URI, bounded unique lowercase
  required headers, and absolute expiry. It is sensitive transient material:
  clients must not persist it, include it in telemetry, or send it through the
  messaging stream.
- Completion names only the stable attachment ID. READY reports stable IDs,
  database transition time, and whether the transition was already complete.
- Rejections use the existing fixed safe `ProtocolError` codes. Foreign,
  revoked, and unavailable attachment IDs must remain non-enumerating.
- Generate Java, C++, and TypeScript bindings from the one schema, but do not
  accept types 120..125 in `V2MessagingHandler` until real-provider acceptance,
  handler/application mapping, telemetry, and supported-client behavior are
  delivered in later slices.

## Consequences

Both supported clients can compile against a stable attachment workflow without
coupling to S3/COS SDK types. The extra authorize round trip preserves clean
idempotency and grant refresh. Simple PUT remains capped by the provider adapter;
the 10 GiB schema ceiling is validation safety, not a supported upload promise.

## Migration and Rollback

This is additive and inactive. V1 attachment JSON/HTTP behavior is unchanged.
Numeric message types are permanent even if implementation rolls back; removed
fields or values must be reserved and never reinterpreted.

## Verification

Java policy tests cover UUIDs, UTF-8 bounds, basename traversal, MIME shape,
size/hash bounds, HTTPS grants, header uniqueness and forbidden stack-managed
headers. One registration fixture has identical deterministic bytes in Java,
generated C++, and generated TypeScript. Full protocol generation and Java
`check` are required. No provider request or product route is enabled.
