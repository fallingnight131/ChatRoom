# ADR-0091: V1 Message Sequence and Read-Cursor Migration

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

V1 reliable messaging uses one durable sequence namespace per room or
friendship. Message creation consumes a sequence, recall stores a later
`mutation_sequence`, and room administrator deletion stores a sequence even
though the deleted message rows are physically absent. The associated high
watermark never moves backwards.

V1 read state is different: `last_read_msg_id` stores an integer row ID from
`messages` or `friend_messages`, not a conversation sequence. IDs are global
within each source table, can have gaps, and can refer past rows that were
physically deleted. Copying this number into V2 `last_read_sequence` would
silently mark unrelated sequence events as read.

## Decision

- Treat the V1 room/friendship high-watermark tables as the authoritative
  allocated cursor range. The imported V2 conversation starts with
  `next_sequence = last_sequence + 1`, preserving creation, mutation, deletion,
  and historical gap positions.
- Validate that every imported conversation has exactly one nonnegative high
  watermark and that every retained creation, mutation, and deletion sequence
  is positive, no greater than that watermark, and unique within the shared
  conversation namespace.
- Require migrated recall state to agree with `mutation_sequence`, with the
  mutation strictly after creation. Do not fabricate mutation history.
- Translate each V1 read pointer conservatively to the greatest retained
  message creation sequence in the same conversation whose legacy message ID
  is no greater than the pointer. Use zero when no retained row qualifies.
- Do not advance read state across later recall or administrative deletion
  sequences. Replaying an already-applied mutation/deletion is safer than
  falsely claiming that a user read unseen message content.
- Keep message payload conversion, tombstone/event representation, and target
  writes out of this planning slice. They require separate verified mappings
  before V1 can cease to be authoritative.

## Consequences

V2 preserves the monotonic cursor range and never reuses a V1 sequence after
cutover. Physical deletion can make the translated read cursor lower than the
user's historical position, causing bounded conservative replay, but cannot
produce a false read acknowledgement. The planner blocks migration when the V1
sequence graph is internally inconsistent rather than repairing it silently.

This decision does not claim that V1 message bodies, recall events, or deletion
events are imported yet. Conversation metadata continues to initialize read
sequences to zero until the verified message-state import applies this plan.

## Verification and Rollback

Unit coverage must include room and friendship namespaces, non-contiguous
message IDs, sequence gaps, recalls, physical deletion events, missing
watermarks, and cursor collisions. The plan must be independent of SQLite row
iteration order.

The implemented source reader requires the complete migrated cursor schema,
runs SQLite `quick_check`, enables `query_only`, and reads conversation and
message state inside one transaction snapshot. It reads no message body, file
payload, credential, or attachment storage path. The reader now includes the
complete durable deletion-command metadata required to rebuild typed deletion
entries; strict validation rejects malformed modes, IDs, operation keys,
fingerprints, cutoffs, counts, and positive-integer JSON arrays.

The plan fingerprint covers the validated conversation fingerprint, every
watermark, retained message ID/sender/creation/mutation/recall/timestamp field,
and deletion event ID/room/operator/sequence/timestamp field. Apply code must
receive an in-memory capability produced by exact current-source, protected
backup, and whole-file SHA-256 reconciliation, then reverify it before commit.

This slice is read-only planning code. Rollback removes the planner and leaves
both V1 and V2 durable state unchanged.
