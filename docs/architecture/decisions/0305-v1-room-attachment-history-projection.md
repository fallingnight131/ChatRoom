# ADR-0305: Preserve imported room attachments in V1 history

- Status: Accepted
- Date: 2026-08-13

## Context

The verified V1 import now persists text and attachment messages in one ordered
conversation sequence. The Java V1 room-history projection previously accepted
only canonical text messages, so the presence of a correctly imported file made
the whole history request fail closed. Omitting the file would silently advance
the synchronization cursor past user-visible history and is not acceptable.

## Decision

Project canonical room attachment messages into the existing V1 history shape.
An attachment row is readable only when its message, ROOM message mapping, ROOM
file mapping, sender mapping, and same-conversation attachment are complete. Its
legacy content type must be `file`, `image`, or `video`, and its canonical state
must be `READY` or `UNAVAILABLE`.

Return the legacy numeric message and file IDs, safe file name and size, original
content type, canonical message sequence and timestamp. Use the file name as the
legacy message content. `UNAVAILABLE` becomes `fileCleared=true` with the bounded
stored reason; `READY` remains active. Never return canonical UUIDs, object keys,
MIME assertions, hashes, provider URLs, local paths, or upload authorization.
Pending, revoked, partially mapped, or type-inconsistent rows make the complete
history projection fail rather than producing a cursor-skipping partial page.

## Consequences

- V1 Web and Windows clients retain mixed text/file ordering after PostgreSQL
  migration and can render cleared historical attachments without downloading
  them.
- Room file-manager authorization remains separate; membership permits history
  visibility but does not grant administrator file-management access.
- Thumbnails are not reconstructed from imported data. A later verified
  derivative-media design may add them without weakening this boundary.
