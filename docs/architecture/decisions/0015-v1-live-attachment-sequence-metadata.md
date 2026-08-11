# ADR-0015: V1 Live Attachment Sequence Metadata

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

Room and friend attachment messages already reserve durable per-conversation
sequences in the same tables used by text and emoji messages. Sequence-based
history returns those values after reconnect, but live `FILE_NOTIFY` and
`FRIEND_FILE_NOTIFY` frames omitted them. Several paths also synthesized a local
wall-clock timestamp instead of publishing the database commit timestamp.

That split means a client observing an attachment cannot treat the live event
as the same ordered record it will later read from history. Reconnect remains
correct only through repeated history reads and stable-ID deduplication, and a
future local outbox/database cannot maintain a contiguous cursor from live
traffic.

## Decision

- Extend the existing database save APIs with optional sequence and timestamp
  outputs. Values are assigned and read inside the message transaction and are
  exposed only after commit succeeds.
- Add the committed `sequence` and database `timestamp` to every successful
  room/friend attachment notification: legacy inline send, HTTP upload
  finalization, and server-side forwarding.
- Preserve wire compatibility. Both fields are additive and old clients may
  ignore them; message type names and existing fields do not change.
- Windows Qt stores the live values in its `Message` model. Web already retains
  additive notification fields in its message objects.
- If file metadata persists but message persistence fails, remove the orphaned
  file row and local path rather than emitting an invalid notification.

This decision does not make upload finalization idempotent and does not claim
exactly-once delivery. A later M1 slice will add retry identity and an explicit
finalization result.

## Consequences

Live attachment events and sequence-history rows now identify the same ordered
record and authoritative time. This enables M2 clients to advance local
conversation cursors uniformly across text and files. The extra timestamp query
is performed only when callers request delivery metadata; legacy non-delivery
call sites keep the previous save cost.

## Verification and Rollback

Real-server smoke tests must require positive sequences and timestamps for
inline, HTTP-uploaded, room-forwarded, and friend-forwarded attachments. Qt
build verification confirms the model consumes both fields. Sequence-history
tests continue to verify ordering and high-watermark behavior.

Rollback removes the additive notification fields while retaining the durable
database sequences. No schema rollback is required.
