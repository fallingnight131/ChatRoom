# ADR-0071: V2 Conversation Directory Wire Contract

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The transport-independent directory is verified against PostgreSQL, but Web and
Windows need one permanent bounded protocol before gateway dispatch can depend
on it. The wire must preserve the composite keyset cursor and distinguish
directory browsing from per-conversation message synchronization.

## Decision

- Permanently allocate message type 110 `ListConversations` (authenticated
  command) and 111 `ConversationDirectoryPage` (response).
- A command has either no cursor or the complete pair of positive server update
  epoch milliseconds and canonical conversation UUID, plus limit 1..100.
- A record contains canonical conversation UUID, registered direct/group kind,
  1..100-code-point bounded display name (at most 400 UTF-8 bytes), registered
  caller role, latest/read sequences, and positive server update time.
- A page contains at most 100 records ordered by `(updatedAt, conversationId)`
  descending. A nonempty page repeats its final composite key as the next cursor;
  an empty page has no cursor. `has_more` is explicit.
- Generate Java, C++, and TypeScript from the same schema and lock the cursor
  command to one fixed golden payload before gateway use.

## Consequences

- Clients can page a deterministic directory and then use each record's sequence
  watermark to plan message synchronization. They must refresh the directory;
  the directory cursor itself is not a durable change feed.
- Member rosters, previews, mute/pin state, creation, and mutation are not added
  implicitly. Each requires an additive schema and authorization decision.
- Values 110 and 111 plus enum values are permanent and cannot be reinterpreted.

## Verification

Java tests cover cursor completeness, limits, response invariants, sequence
bounds, and same-timestamp UUID ordering. Generated TypeScript and compiled C++
parse and re-encode the exact Java golden command.

## Rollback

Remove the unconsumed schema, policy, and registry references before client
release. After release, reserve numeric values rather than reuse them.
