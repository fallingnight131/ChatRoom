# ADR-0070: V2 Conversation Directory Model

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

Authenticated V2 clients can submit/read messages only when they already know a
conversation UUID. A supported client needs a bounded server-authorized
directory containing enough canonical state to render and start sequence sync.
The V001 schema has no group title, and offset pagination would become unstable
as conversations receive messages.

## Decision

- Add nullable storage for `conversation.title`, deterministically backfill any
  pre-product group rows, then constrain direct titles to null and group titles
  to 1..100 characters. Direct display names are projected from the other
  account; group display names come from the server-owned title.
- Define a transport-independent directory port returning conversation UUID,
  direct/group kind, display name, caller role, latest sequence, caller read
  sequence, and authoritative update time.
- Authorize only a non-disabled account's active memberships. A left membership
  and a disabled account produce no directory rows rather than revealing that a
  conversation exists.
- Page at 1..100 rows in descending `(updated_at, conversation_id)` order. The
  next cursor contains both values, eliminating offset drift and timestamp ties.
  It is a browsing cursor, not a durable message synchronization cursor.
- Keep member lists, conversation creation/mutation, and the wire contract out of
  this storage slice; they require separate authorization and compatibility
  decisions.

## Consequences

- Web and Windows can later discover authorized conversations without querying
  SQL shapes or guessing direct peer labels.
- A conversation updated ahead of a cursor during pagination may be observed on
  the next directory refresh; message correctness still comes from per-
  conversation sequence synchronization.
- V004 is forward-only. Existing pre-product group rows get a deterministic
  placeholder that must be replaced during the later verified conversation
  import.

## Verification

Application tests enforce bounds, sequence invariants, and exact composite
cursors. The disposable PostgreSQL gate migrates through V004 and proves direct
peer/group titles, descending tie-safe paging, high watermarks, active-
membership filtering, and disabled-account denial against a real database.

## Rollback

Disable the unused directory adapter. Do not down-migrate V004; the additive
column and index are harmless while V1 remains authoritative.
