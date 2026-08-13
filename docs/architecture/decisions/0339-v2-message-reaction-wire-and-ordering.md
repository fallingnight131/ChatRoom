# ADR-0339: V2 Message Reaction Wire and Ordering

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

A reaction is not presentation-only metadata. It is a multi-device durable
mutation that must survive reconnect, preserve conversation ordering, converge
under retries, and disappear consistently when the target message is no longer
eligible. Sending an unordered aggregate snapshot beside messages would create
snapshot/live gaps and make incremental synchronization depend on timing.

The durable model already has one mixed `conversation_entry` sequence for
messages, recalls, and deletions. Reactions should extend that authority rather
than create a timestamp-ordered side channel.

## Decision

- Permanently allocate message types 106--108 for `SetMessageReaction`,
  `MessageReactionApplied`, and `MessageReactionChanged`.
- Add an explicit `MESSAGE_REACTIONS` handshake capability. The server enables
  it only when requested, accepts reaction commands only on enabled sessions,
  and sends type-108/live reaction details only to those sessions. For an older
  V2 client, history may omit reaction details while still advancing the page's
  authoritative mixed cursor across those entries; it must never send an
  unknown partial detail. This preserves gap-free message sync during the
  preview compatibility window.
- Start with six protocol enum identities: like, love, laugh, surprised, sad,
  and angry. Clients may localize or render them as emoji but must not transmit
  arbitrary Unicode or reinterpret an existing numeric value. New reactions are
  additive enum values after all clients tolerate unknown values safely.
- A command carries canonical conversation/message IDs, the reaction identity,
  desired active state, and an opaque bounded client operation ID. Identity and
  authorization come only from the authenticated session.
- Persist exact operation outcomes. Reusing one client operation ID with the
  same command returns the original result with `duplicate=true`; changing any
  bound field is an idempotency conflict.
- Allocate a new conversation sequence only when the desired state changes.
  A convergent no-op returns `changed=false` and sequence zero; a changed result
  returns `changed=true` and a positive sequence. Clients must not advance their
  sync cursor from a no-op response.
- Represent each changed reaction as a `ConversationEntryRecord` detail and as
  the type-108 live event. History and live delivery therefore use the same
  server sequence and gap-repair rules as messages, recalls, and deletions.
- The durable state is one active row per message/account/reaction. The event
  records retain add/remove history and operation identity; current aggregates
  are projections, not ordering authority.
- The persistence slice must authorize active membership/device state, reject
  missing/recalled/deleted targets, serialize against the conversation sequence,
  and commit operation result, state projection, and changed event atomically.

## Consequences

Reaction traffic participates in the conversation sequence and can increase
history volume. This is intentional correctness-first behavior; a future
compaction/snapshot design must preserve an exact resume boundary and requires a
new ADR.

The fixed initial enum avoids Unicode normalization, grapheme, skin-tone, and
cross-font ambiguity. It is less flexible than arbitrary emoji, but creates one
consistent Web/Windows behavior and a bounded abuse surface.

Protocol publication alone does not activate a gateway route. PostgreSQL,
gateway, local-cache, Web, and Windows slices remain separately gated.
Web and Windows must not advertise the capability until their durable local
projection and UI slices are complete.

## Verification

Java policy tests require canonical identities, bounded operation IDs, supported
reaction values, and the changed/sequence invariant. Java, TypeScript, and C++
golden tests encode/decode the same deterministic command bytes. History policy
tests reject missing details, unsupported reaction values, mismatched identities,
non-positive sequences, and non-positive event timestamps.

## Rollback

Keep numeric values 106--108 and the Protobuf fields reserved if the feature is
withdrawn. Since no gateway route or durable schema is activated by this slice,
rollback removes registry handling and client generation without data migration.
