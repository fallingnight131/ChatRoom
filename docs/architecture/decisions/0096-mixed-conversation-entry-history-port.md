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
  message-only port. Do not change the meaning of `MessageHistoryPort`.
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
- Do not route this port to the existing V2 wire message yet. A later additive
  protocol field/message and coordinated Web preview update must preserve old
  field meanings and validate mixed ordering before activation.

## Consequences

The server now has one authoritative, gap-free read model for imported message
and mutation history without changing active client behavior. The legacy
message-only adapter remains available during the compatibility window and can
be removed only after the mixed wire/client path is verified.

## Verification and Rollback

Disposable PostgreSQL verification imports two messages, one recall, and one
deletion event, then reads all four entries in exact sequence order. It proves
the recall's unknown time remains absent and the deletion's V1 message ID maps
to the deterministic V2 UUID. Membership authorization is shared with the
existing history boundary.

Rollback removes the unused additive application port and adapter method. No
schema, imported data, protocol registry, or client behavior changes in this
step.
