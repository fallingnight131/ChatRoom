# ADR-0099: Inactive S3 Attachment Object-Store Adapter

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

ADR-0098 defines a provider-neutral create-only upload contract. The Java
backend needs a concrete implementation without coupling the application module
or gateway runtime to Tencent COS, Amazon S3, credentials, endpoints, or an HTTP
client implementation.

AWS SDK for Java 2.x exposes `contentLength`, `checksumSHA256`, and
`ifNoneMatch` on `PutObjectRequest`, and its presigner includes those values in
the signed request. S3 `HeadObject` can request and return the stored SHA-256.
Tencent documents S3 SDK compatibility for COS, but generic compatibility does
not prove that every integrity header, CORS policy, or HEAD checksum behavior
required here works for a particular bucket.

## Decision

- Add a separate `object-storage-s3` adapter module. It depends inward on the
  application port; neither the application module nor inactive gateway paths
  depend on it.
- Pin and lock AWS SDK for Java 2.x. Exclude its Apache and Netty HTTP clients
  and retain the JDK URLConnection client so this future integration cannot
  introduce a second Netty line into the gateway runtime.
- Presign one exact PUT with bucket, server-generated key, content type, byte
  length, Base64 SHA-256, and `If-None-Match: *`. Return only signed headers that
  callers may set; Host and Content-Length remain browser/HTTP-stack managed.
- Reject single-PUT objects above 5 GiB. The 10 GiB database safety ceiling is
  not a product promise; larger files require a separately reviewed,
  restartable multipart protocol with per-part integrity and abort cleanup.
- Inspect completion with checksum mode enabled. Treat only provider 404 as
  missing. A missing, malformed, or non-SHA-256 checksum and every other
  provider error fail closed rather than producing trusted object metadata.
- Implement cleanup deletion only for the server-owned `attachments/` key
  namespace. S3 success and 404 are idempotent completion; authorization,
  throttling, timeout, and other provider failures remain retryable failures.
- Keep the module inactive. Do not read ambient credentials or add it to the
  gateway composition until strict environment configuration, startup
  capability checks, CORS verification, telemetry, and cleanup exist.
- Before enabling Amazon S3, COS, MinIO, or another compatible service, run an
  acceptance test against the real bucket proving create-only overwrite
  rejection, SHA-256 validation and HEAD retrieval, expiry, CORS for Web, and
  deletion of abandoned pending objects. S3 compatibility alone is not
  acceptance evidence.

## Consequences

The application orchestration now has a concrete adapter without exposing
provider types across the port. Web and Windows can eventually use the same
signed request constraints. The adapter currently supports only simple PUT;
multipart uploads and an active runtime remain later slices.

Dependency size increases only in the isolated module. No credential, endpoint,
bucket name, signed URL, or production request is committed or executed.

## Verification and Rollback

Tests use the real AWS presigner with fixture credentials and no network. They
prove the signed HTTPS request contains exact content type, SHA-256, and
create-only conditions; excludes Host/Content-Length from client-set headers;
enforces lifetime and single-PUT bounds; requests checksum-enabled HEAD; maps
valid metadata; treats 404 as missing; and fails closed on denial or absent
checksum. Delete tests prove exact bucket/key projection, missing-object
idempotency, key-prefix enforcement, and failure on provider denial. Dependency
locks prove the excluded HTTP clients are absent.

Rollback removes the inactive module, catalog entries, dependency lock, and
settings inclusion. It changes no runtime composition, wire protocol, database,
credential, or object.
