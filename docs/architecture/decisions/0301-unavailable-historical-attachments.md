# ADR-0301: Represent cleared V1 files without fabricated object evidence

- Status: Accepted
- Date: 2026-08-13

## Context

Some V1 file rows have been cleared by retention or administration. Their
messages and safe metadata remain durable, but their bytes may no longer exist.
The canonical attachment table previously required an object key, asserted MIME
type, and SHA-256 for every lifecycle state. Inventing any of those values would
make an unavailable historical file look downloadable and corrupt durable truth.
Dropping the message would also damage conversation history and sequence parity.

## Decision

Add a terminal `UNAVAILABLE` attachment state for historical imports. It retains
the canonical identity, conversation and owner, client identity, safe filename,
declared byte size, creation time, unavailable time, and a bounded reason. It
requires object key, media type, SHA-256, ready/revoked timestamps, and deletion
confirmation to be absent.

Existing `UPLOAD_PENDING`, `READY`, and `REVOKED` rows continue to require exact
object key, media type, and 32-byte SHA-256 evidence. Runtime upload authorization
and completion accept only pending/ready objects; unavailable history cannot be
resurrected through those paths. Cleanup continues to select only revoked rows
with real server-owned object keys.

## Consequences

- Cleared V1 messages can remain in ordered history without implying that bytes
  or a target object exist.
- Active V1 files still require independently verified MIME, size, SHA-256,
  target object key, and sealed-object evidence before import.
- Product clients must render `UNAVAILABLE` as retained metadata with a clear
  non-downloadable state, not as a retryable upload.
- V029 is an expand migration. The inactive Java path remains the only consumer
  until the attachment importer, history projection, and external cutover gates
  are complete.
