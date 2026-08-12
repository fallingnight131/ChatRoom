# ADR-0098: V2 Create-Only Attachment Upload Boundary

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

ADR-0097 reserves durable attachment metadata but deliberately issues no
storage authorization. A direct bucket credential, reusable write permission,
or unchecked `HEAD`-then-READY flow would let clients replace bytes after
verification, access another object's key, or make a message reference content
that does not match its declared size and SHA-256.

## Decision

- Add a provider-neutral object-store port. It accepts only a server-derived
  object key, canonical media type, exact byte size, and SHA-256.
- Issue HTTPS PUT grants for one exact object and no more than ten minutes.
  Grants are transient secrets: never persist or log their URI or signed
  headers, and never expose bucket credentials.
- Require every concrete object-store adapter to enforce create-only upload and
  trusted SHA-256 integrity. The provider may implement this with a signed
  create-if-absent condition, immutable object version, or staging-to-immutable
  promotion, but a reusable overwrite-capable URL does not satisfy the port.
- Model completion as inspection of a sealed object. READY requires exact
  object key, byte size, and SHA-256. Missing or mismatched objects remain
  `UPLOAD_PENDING`.
- Recheck active account, membership, device ownership, device revocation, and
  attachment state when changing PostgreSQL to READY. Do not hold a database
  transaction open across an object-store request.
- Make completion idempotent. An already READY attachment returns its stable
  metadata without another object-store request. A concurrent winner returns
  the same READY row.
- Add a PostgreSQL lifecycle adapter that locks the attachment row, rechecks
  active membership/account/device authorization, and performs an idempotent
  READY transition. Keep the boundary inactive until a concrete object-store
  implementation, runtime configuration, metrics, and wire command exist.
  Transport identity must come from the authenticated session rather than
  request fields.

## Consequences

Application policy is independent of COS, S3, or another provider, and file
bytes never enter chat frames or PostgreSQL. Object-store adapters have a
stronger contract than ordinary presigned PUT generation; providers that cannot
guarantee create-only integrity need a staging/promotion design before use.

The application model contains a temporary signed HTTPS URI and required
headers only in the response object. Callers are responsible for redacting the
entire grant from logs and traces.

## Verification and Rollback

Application tests prove pending-only authorization, bounded expiry, generic
foreign/READY rejection, exact sealed-object verification, no transition for
missing or mismatched objects, idempotent READY completion, and failure when
authorization changes before the transition.

Disposable PostgreSQL integration tests race two READY transitions and prove
one state change plus one idempotent result with the same timestamp. They also
prove foreign-device lookup rejection and rejection after device revocation.

Rollback removes the inactive application service, ports, and lifecycle adapter
methods. It changes no wire protocol, runtime configuration, stored schema, or
Flyway migration.
