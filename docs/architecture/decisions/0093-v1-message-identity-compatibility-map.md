# ADR-0093: V1 Message Identity Compatibility Map

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

Retained V1 room and friendship messages use separate integer-ID namespaces.
V2 uses UUID message identities. Recomputing or exposing legacy IDs in the V2
domain would make reconnect, recall projection, and rollback translation
ambiguous. Physically deleted V1 messages have no target message row, while
durable deletion events still require their own source identity.

## Decision

- Add `legacy_v1_message_map` outside the core message model with a typed
  `(ROOM|FRIENDSHIP, legacy_message_id)` source key and a unique V2 message UUID.
- Require the source conversation identity and target `(conversation, message)`
  to match through composite foreign keys. A message can never be remapped into
  another conversation.
- Add a separate `legacy_v1_deletion_event_map` from the global V1 deletion-event
  ID and room to a `MESSAGES_DELETED` conversation entry. Do not fabricate
  message mappings for rows that V1 physically removed.
- Expose retained-message translation through a read-only application port.
  Authorization remains the caller's responsibility; the projection neither
  grants membership nor creates mappings.
- Keep these tables through the V1 compatibility and rollback window. Removing
  them requires a later ADR and confirmed end of V1 projection needs.

## Verification and Rollback

Disposable PostgreSQL tests cover clean/restart migration, exact lookup in both
directions, namespace isolation, conversation mismatch rejection, and target
uniqueness. The migration is additive; before imports, rollback removes the
projection. After imports, rollback requires the documented database restore
path so identity translation is not lost independently of message data.
