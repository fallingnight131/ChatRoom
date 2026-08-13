# ADR-0329: Durable V2 Reply Reference

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

ADR-0328 defines a reply command and server-authored record reference. Product
handling requires atomic target validation, message sequencing, idempotency, and
history/live projection. A target may later be recalled or deleted; a reply must
not retain its body, but it must retain enough identity to render an unavailable
state. Concurrent reply, recall, and deletion must not create a reference that
was never valid in the conversation.

## Decision

- Extend the transport-neutral submission with an optional target message UUID.
  Persist the authoritative result as an optional immutable
  `MessageReplyReference` containing target UUID, target conversation sequence,
  and target sender account UUID.
- V044 adds `chat.message_reply_reference`. Its current-message foreign key
  cascades when the reply is deleted; the target identity is deliberately not a
  foreign key so later target deletion cannot erase or rewrite the reply's
  unavailable-state identity.
- A database trigger accepts an insert only when the reply and target exist in
  the same conversation, the supplied target sequence/sender match durable
  truth, and the target sequence precedes the reply. Updates are forbidden.
- For a new submission, first authorize and check the account-scoped
  idempotency key. Then lock/advance the conversation sequence before resolving
  the target, serializing this decision with recall/deletion sequence mutation.
  Missing, cross-conversation, or already-recalled targets receive the same
  opaque not-authorized result. Rollback restores the unused sequence.
- The idempotency fingerprint includes the optional target UUID. Exact retries
  return the original server-authored reference; reuse with a different target,
  content, device, type, or conversation is a conflict.
- Install type 105 in the existing bounded, per-connection Java messaging
  executor. Reuse existing accepted/denied/conflict/failure telemetry. History
  and live events map only the durable reference; no target body is copied.

## Consequences

Reply acceptance is one transaction and preserves per-conversation order. The
normal message table remains the durable content truth; the relation table is
small and indexed for target-oriented maintenance. Clients can synchronize a
reply before its target and later resolve it from normal history without a
separate quote cache.

V1 clients and ordinary V1 messages remain unchanged. Web and Windows product
UI is still pending, so this backend capability alone does not expose replies to
supported users.

## Verification

- application tests cover optional target/reference invariants and legacy
  constructor compatibility;
- gateway tests cover type-105 parsing from server-bound identity and reply
  projection in message history;
- disposable real PostgreSQL tests cover clean V044 migration, restart,
  concurrent-safe append, exact duplicate replay, changed-target conflict,
  missing-target denial, and history round-trip;
- the full Java gate verifies every consuming module after the schema change.

## Rollback

Disable type-105 dispatch first. Existing V044 rows are additive and may remain
while older code runs because ordinary message reads ignore the relation table.
Drop the table/function/trigger only after confirming no released client relies
on reply projection; never reuse the published protocol identifiers.
