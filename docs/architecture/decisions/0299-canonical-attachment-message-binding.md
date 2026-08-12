# ADR-0299: Bind canonical messages to attachments before V1 file import

- Status: Accepted
- Date: 2026-08-13

## Context

The V2 attachment registry owns object metadata and lifecycle, but a durable
message cannot yet reference an attachment. V1 message import consequently
rejects every file/image message with `ATTACHMENT_MAPPING_REQUIRED`. Importing
those rows as text would lose file identity, while storing a server-local V1
path in a message or object key would create an unsafe and non-portable result.

V1 numeric file IDs are also scoped by separate room and friendship tables, so
they require the same typed compatibility isolation used for conversations and
messages.

## Decision

V028 adds nullable `message.attachment_id` with a same-conversation foreign key
to the canonical attachment registry. Content type 2 is permanently reserved
for attachment messages: it requires exactly one attachment and an empty
payload. Every other current message type must have no attachment. One
attachment can be bound to at most one canonical message.

Add `legacy_v1_attachment_map`, keyed by `(legacy_kind, legacy_file_id)`. It
requires a positive typed V1 source identity, the exact imported conversation
mapping, and a same-conversation canonical attachment. One canonical attachment
can have only one V1 source identity. File paths, URLs, filenames, hashes, and
object credentials are not stored in the compatibility map.

This migration does not import a V1 file. A later verified input must reconcile
the SQLite file row, its referencing message, uploader, local/object evidence,
size, SHA-256, MIME, lifecycle, and target object key before creating an
attachment, mapping, and attachment message atomically.

## Consequences

- Canonical history can gain attachment records without embedding file bytes or
  V1 storage details in message payloads.
- Missing object evidence remains a blocking migration issue instead of being
  hidden behind fabricated metadata.
- Rollback before authority cutover removes the inactive code; schema rollback
  requires restoring the pre-V028 PostgreSQL backup or a reviewed forward fix.
