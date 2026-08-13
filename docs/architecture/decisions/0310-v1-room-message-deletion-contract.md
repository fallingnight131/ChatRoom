# ADR-0310: V1 Room Administrative Message-Deletion Contract

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3

## Context

The detached Java V1 gateway can delete selected room files, while the existing
`DELETE_MSGS_REQ` command also lets an administrator delete selected messages,
all messages, messages strictly before a timestamp, or messages strictly after
a timestamp. PostgreSQL already contains the imported deletion-event model and
room history can replay it, but there is no Java runtime command contract for
these four modes.

This boundary must remain compatible with Web and Windows V1 clients without
letting transport-supplied identity, timestamps, or retry keys weaken server
authorization and durable idempotency.

## Decision

- Add a transport-independent V1 room-message deletion use case. The gateway
  supplies the authenticated account; the application never accepts an actor
  from the request body.
- Preserve the exact lowercase V1 modes `selected`, `all`, `before`, and
  `after`. Selected mode requires 1-100 distinct positive legacy message IDs
  and forbids a cutoff. Predicate modes forbid IDs; before/after require a
  positive cutoff and all forbids one.
- Normalize selected IDs into ascending order and normalize before/after cutoff
  timestamps down to whole seconds, matching the current SQLite service.
- Bind room, mode, normalized IDs, and normalized cutoff into a SHA-256 command
  fingerprint. Idempotency remains scoped to authenticated actor plus the
  bounded `clientOperationId`; a mismatched retry is a conflict.
- Persistence must authorize an active OWNER/ADMIN and perform target
  resolution, attachment revocation, message/recall removal, shared sequence
  allocation, deletion-event append, and legacy event mapping in one
  serializable transaction.
- Predicate events keep `messageIds` empty because clients apply their mode and
  cutoff locally. Selected events expose only actually deleted mapped IDs.
  Deleted file IDs remain bounded by the V1 room resource contract and drive
  asynchronous object cleanup after commit.
- Exact retries return the first durable outcome and never emit a second live
  effect. Empty target sets are still successful durable events, preserving the
  established V1 behavior and deterministic retry outcome.
- Keep this handler detached from the product listener until the compatibility,
  migration, shadow, canary, rollback, and observability gates permit cutover.

## Consequences

The Java application boundary now has one canonical interpretation of all four
legacy delete modes and rejects ambiguous payloads before storage. Bulk
predicate deletion remains one atomic moderator action and one conversation
sequence rather than generating per-message tombstones.

Physical object removal remains post-commit compensation. Revoked attachment
metadata is retained so cleanup can be retried without restoring deleted
message bodies or leaking canonical IDs to V1 clients.

## Verification

- application tests cover normalization, all four payload shapes, operation-ID
  bounds, duplicate IDs, and persistence identity drift;
- PostgreSQL tests must cover authorization, strict cutoff boundaries, selected
  and predicate modes, recalls, attachments, empty matches, exact/conflicting
  retries, and sequence/event integrity;
- gateway tests must cover malformed frames, saturation, compatible response
  and notification shapes, and first-commit-only live effects;
- real PostgreSQL gateway evidence must prove login-to-delete and reconnect
  history replay before this slice is marked composed.
