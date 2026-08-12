# ADR-0096: Mixed Conversation Entry History Port

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

V2 message history currently reads only `message`. After V1 import, a
conversation sequence can also contain recall and administrative-deletion
entries. Advancing a cursor over only message rows either stalls at a mutation
gap or silently loses the mutation. Reinterpreting the existing message-only
wire field would break preview clients and violate protocol versioning.

## Decision

- Add a transport-independent `ConversationEntryHistoryPort` beside the existing
  message API. Preserve the meaning of its `messages` list while letting its
  page carry additive entries and use the authoritative entry cursor.
- Model the ordered stream as sealed message, recall, and deletion entry types.
  Unknown V1 recall time remains absent; it is never replaced with import time.
- Page and authorize from the durable `conversation_entry` sequence. Every row
  must resolve to its typed detail table or the read fails closed.
- Translate V1 numeric deletion target IDs through
  `legacy_v1_message_map` at the PostgreSQL boundary. Expose stable V2 message
  UUIDs to the application model; do not leak legacy JSON shapes inward.
- Keep command fingerprints and legacy file IDs out of the application history
  projection. Attachment identity translation is deferred to the attachment
  migration boundary.
- Add `MessageHistoryPage.entries` at new field 6. Preserve the existing
  `messages` field as a creation-message mirror; never reinterpret its records.
  Each entry repeats its conversation/sequence identity and carries exactly one
  typed message, recall, or deletion detail.
- Route V2 history pagination over the mixed entry port. The cursor identifies
  the last returned entry, including mutation-only pages. Emit both fields and
  update the isolated Web preview in the same release; new Web code prefers
  entries and falls back to messages when talking to a compatible older server.

## Consequences

The server and isolated Web preview now share one authoritative, gap-free read
model for imported message and mutation history. An older preview client still
decodes the message mirror but cannot render new mutation detail, so preview
server/client rollout is coordinated. The supported V1 Web product path remains
unchanged.

## Verification and Rollback

Disposable PostgreSQL verification imports two messages, one recall, and one
deletion event, then reads all four entries in exact sequence order. It proves
the recall's unknown time remains absent and the deletion's V1 message ID maps
to the deterministic V2 UUID. Java protocol/gateway tests cover typed encoding,
unknown recall time, and malformed detail rejection. Web tests cover strict
decode plus ordered recall/deletion application and exact cursor advancement.
Membership authorization is shared with the existing history boundary.

Rollback stops emitting field 6 and restores the Web message-field fallback;
field number 6 remains permanently reserved. No schema or imported data is
removed. The inactive application port may remain because it has no independent
traffic authority.
