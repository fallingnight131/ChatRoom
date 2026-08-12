# ADR-0097: V2 Attachment Metadata Registry

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

V1 retained attachment messages cannot be imported safely as text and must not
carry file bytes or server-local paths into the V2 message payload. Before
upload authorization or V1 attachment mapping can be added, PostgreSQL needs a
durable metadata identity with explicit lifecycle and idempotency constraints.

## Decision

- Add an `attachment` registry owned by a conversation, account, and device.
  Database foreign keys require the owner to be a conversation member and the
  device to belong to that account.
- Use server UUID identity and a server-generated object key. The original file
  name is presentation metadata only and is never used as an object key.
- Make `(owner_account_id, client_attachment_id)` unique for future idempotent
  registration. Make object keys globally unique and `(conversation_id, id)`
  available for a future attachment-message foreign key.
- Store only bounded filename, media type, byte size, expected SHA-256, lifecycle
  timestamps, and state. Do not store file bytes, local paths, bucket endpoints,
  access credentials, upload URLs, or download URLs.
- Bound one object to 10 GiB, matching the current V1 room setting ceiling. This
  is a schema safety ceiling, not a product promise; application policy may be
  lower by conversation and client capability.
- Start with `UPLOAD_PENDING`, `READY`, and `REVOKED`. PostgreSQL enforces state
  timestamp consistency. Object-store verification is required before a future
  application service may transition a row to `READY`.
- Add a transport-neutral registration port. It validates basename/path safety,
  bounded UTF-8 identifiers, canonical parameter-free MIME type, size, and exact
  SHA-256 before persistence.
- In the PostgreSQL adapter, lock and require an active conversation membership,
  enabled account, and owned non-revoked device in the registration transaction.
  Server-bound identity must populate the command when a future transport is
  added; payload identity can never grant access.
- Treat the owner-scoped client attachment ID as an idempotency key. An exact
  concurrent or later retry returns the original UUID, object key, lifecycle,
  and creation time with `duplicate=true`. Any metadata difference returns an
  opaque idempotency conflict and creates no row.

## Consequences

Attachment identity and metadata can evolve independently of message payloads
and storage authorization. The application boundary is currently inactive: it
exposes no wire command, grants no upload, reads no object, and does not unblock
V1 attachment import. Gateway telemetry is therefore deferred until a command
can actually invoke the boundary.

## Verification and Rollback

Disposable PostgreSQL verification applies all twelve migrations, validates a
same-database restart, inserts one pending registry row, rejects duplicate
client identity and malformed SHA-256, rejects an unverified ready transition,
and accepts a ready transition only with its timestamp. Adapter integration
verification races two exact registrations, proves one durable row and stable
identity, rejects conflicting reuse and an outsider, returns existing READY
metadata on exact retry, and rejects a revoked device.

Rollback of the inactive code removes the application port and adapter. Schema
rollback restores the database from the pre-V012 backup or applies a separately
reviewed forward migration. Never edit or delete an applied Flyway migration.
