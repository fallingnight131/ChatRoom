# ADR-0101: Durable Attachment Object-Cleanup Marker

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

An upload can expire, be revoked, or fail after bytes reach object storage. A
background worker that only deletes an object and logs success cannot recover
correctly after process failure: it may forget a required delete or repeatedly
guess whether a durable attachment still owns the key.

The current `REVOKED` state records that an attachment is unavailable, but it
does not distinguish an outstanding object deletion from a confirmed deletion.

## Decision

- Expand the attachment registry with nullable `object_deleted_at`.
- Permit this timestamp only for `REVOKED` rows with `revoked_at` present, and
  require it to be no earlier than revocation.
- Add a partial ordered index for `REVOKED` rows whose object deletion is still
  outstanding. A future bounded cleanup worker will page this durable set.
- Use the recoverable order: commit REVOKED first, delete the server-generated
  object key outside the SQL transaction, then mark `object_deleted_at` only
  after the provider confirms idempotent deletion.
- Never mark object deletion before or in the same transaction as an external
  delete request. A provider timeout leaves the durable row eligible for retry.
- Keep the column nullable and do not backfill or reinterpret existing rows.
  This is the expand phase; no active upload or cleanup command exists yet.

## Consequences

PostgreSQL becomes the durable source of cleanup work without storing provider
URLs, credentials, or responses. Duplicate object deletion attempts are allowed
and must be safe in the future provider adapter. A later worker still needs
bounded batches, retry/backoff, metrics, and an operational age policy.

## Verification and Rollback

Disposable PostgreSQL verification applies all thirteen migrations, rejects an
object-deleted timestamp on a READY row, and accepts a REVOKED row whose delete
timestamp follows revocation. Existing attachment constraints and adapter tests
continue to pass.

Rollback restores the pre-V013 backup or uses a separately reviewed forward
migration. Never edit or remove the applied Flyway migration.
