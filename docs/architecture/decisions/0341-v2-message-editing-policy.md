# ADR-0341: V2 Message Editing Policy and Revision Ordering

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

Web and Windows V2 previews now converge messages, replies, reactions, and pins
through stable identities and one mixed conversation sequence. Message editing
must preserve that ordering while preventing a stale offline device from
silently overwriting a newer edit. V1 clients have no edit event or revision
model, and copied revision bodies would increase privacy, retention, and
moderation risk.

The desired user outcome is a clear, bounded edit action on the author's recent
text message, an explicit edited marker, deterministic multi-device conflict
behavior, and reconnect recovery through the existing sequence cursor.

## Decision

- Add an independently negotiated `MESSAGE_EDITS` capability. Permanently
  allocate type 114 for `EditMessage`, type 115 for the correlated
  `MessageEditApplied` response, and type 116 for the uncorrelated
  `MessageEdited` event. Unsupported peers reject or never receive these types.
- An edit command carries canonical conversation/message IDs, the caller's
  expected nonnegative content revision, UTF-8 text content, and a stable
  client-generated operation ID. The authenticated session supplies actor and
  device identity; client role, timestamp, and sequence are never authoritative.
- Only the original author may edit an active, non-recalled, non-deleted,
  V2-native text message while still an active conversation member. Group
  OWNER/ADMIN roles do not grant editing of another member's message; moderation
  continues to use recall or administrative deletion.
- Use database time and a fixed 15-minute window from durable acceptance. Limit
  text to the existing 65,536-byte UTF-8 bound and limit one message to 100
  successful content revisions. Empty or invalid UTF-8 content is rejected.
- Revision zero is the accepted original. A changed edit requires
  `expected_revision` to equal the locked current revision, increments it by
  one, consumes the next conversation sequence, and records database time. A
  same-content edit at the current revision is a convergent no-op with
  `changed=false`, sequence zero, and no revision increment.
- Exact operation retries return the original result. Reusing an operation ID
  with different message, expected revision, or content is an idempotency
  conflict. A nonduplicate stale revision is a permanent conflict and must
  return no current body through the error path.
- `MessageRecord` gains additive current revision and edited-at metadata while
  retaining the current authoritative body. Changed edit entries carry target
  identity, resulting revision/content, actor identity, operation identity, and
  occurrence time. History and live consumers apply an edit only when it moves
  the target from exactly revision N to N+1; a gap triggers history repair.
- PostgreSQL owns current message content and revision plus exact operation
  outcomes and ordered edit entries. Revision audit rows may retain the prior
  and resulting body only for the product's documented retention window;
  recall or administrative deletion must erase all retained revision bodies in
  the same transaction. Metrics and normal logs never contain message content.
- V1-authored or V1-mapped messages are not editable during the compatibility
  window. V1 clients receive no edit events. An older V2 session without the
  capability may omit edit details while its history cursor advances, just as
  for reactions and pins; it regains current content only through a complete
  authoritative message projection.
- Web IndexedDB and Windows SQLite persist a bounded edit command before send
  and keep authoritative content separate from the optimistic overlay. ACKs
  clear the exact command but never advance the history cursor. A stale-revision
  failure preserves the proposed text visibly and requires explicit user
  rebase/retry after history repair; clients must not automatically substitute a
  newer expected revision.
- Permanently allocate protocol error codes 9, 10, and 11 for revision conflict,
  edit-window expiry, and revision-limit exhaustion. Clients branch on these
  codes, never on localized `safe_message` text; all three are non-retryable
  without an explicit user action or a different command.
- Do not advertise `MESSAGE_EDITS` from a client or enable it from the gateway
  until PostgreSQL, gateway, history/live, cache, conflict UI, accessibility,
  and reconnect gates for that endpoint pass.

## Alternatives Considered

- Last-write-wins without an expected revision was rejected because an offline
  device could silently erase a newer edit.
- Editing indefinitely was rejected for the first slice because it expands
  abuse, audit, and retention obligations. A later policy change requires an
  ADR and explicit product migration.
- Storing only the latest body with no ordered edit entry was rejected because
  connected clients and reconnect history could not converge on one cursor.
- Reusing ordinary message publication was rejected because old servers could
  reinterpret edits as new messages and idempotency would become ambiguous.
- Enabling edits for V1-mapped messages was rejected while supported V1 clients
  cannot observe or reconcile the mutation.

## Consequences

Users get predictable editing and explicit conflict behavior across devices.
The server pays one serialized message mutation and one mixed-sequence entry per
changed edit. The revision bound limits write amplification, but retained
revision content requires a documented privacy/retention policy before runtime
activation. Older clients can remain connected but may display stale edited
content until a full refresh, so capability rollout and compatibility telemetry
are release gates.

Mentions, rich text, attachments, collaborative editing, administrator body
rewrites, edit notifications, and edit-history UI remain out of scope.

## Migration and Rollback

Add nullable/defaulted current-revision metadata, edit operation outcomes, and
ordered edit entries in a new forward-only Flyway migration. Existing messages
start at revision zero; no content backfill is required. Keep gateway capability
enablement and both client advertisements default off until their vertical
slices complete.

Before activation, rollback removes the capability from negotiation and leaves
the additive tables/columns unused. After edits are accepted, binaries may roll
back only to a version that can filter edit entries while preserving sequence
cursors and project current message bodies. Applied migrations are never edited
or removed; incompatible rollback requires restore or a reviewed forward fix.

## Verification

Require Java/C++/TypeScript golden-wire tests; clean/restart/checksum PostgreSQL
migration tests; author, membership, V2-origin, content, window, and revision
bounds; first/no-op/exact/conflicting/stale/concurrent operations; recall and
deletion body erasure; capability-filtered history/live delivery without cursor
stall; fixed-cardinality metrics with no content; Web/Windows restart-safe
outboxes, optimistic overlays, ACK-without-cursor-advance, gap repair, explicit
conflict/rebase UX, keyboard accessibility, and old-client/new-server coverage.
