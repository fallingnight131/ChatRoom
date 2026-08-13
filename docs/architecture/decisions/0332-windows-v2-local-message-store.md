# ADR-0332: Windows V2 Local Message Store

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

Windows V1 cache rows use legacy integer identities and serialized V1 message
objects. V2 reply composition needs UUID message identity, signed 64-bit
conversation sequences, durable optimistic state, and immutable reply target
identity. Extending the V1 row shape would couple the cutover preview to legacy
serialization and make rollback harder.

## Decision

- Add a separate account-scoped `v2-messages.sqlite` repository for the
  default-off V2 path. Keep its schema and API independent of the V1 local
  repository until cutover migration is explicitly designed.
- Persist conversation cursor and draft plus bounded text message rows keyed by
  account, conversation, and client message ID. Store delivery state as pending,
  failed, or accepted; reconcile ACKs into the same row and merge authoritative
  history/live records by client or server message identity.
- Apply each history page and its continuation cursor in one SQLite transaction,
  including mutation-only pages. Merge live events without advancing the durable
  history cursor so a stream gap is still repaired by the next history read.
- Apply recall/deletion entries inside that same page transaction. Recall erases
  cached target text and marks the row unavailable; administrative deletion
  removes the target row. Reply rows retain only target identity and therefore
  render an unavailable reference when their target is absent or recalled.
- Persist only reply target message UUID, target sequence, and target sender
  account UUID. Never persist a copied quote body or transient transport data.
- Preserve failed intent for explicit user retry, but replay only pending rows.
  Bound non-accepted rows to 256 per account and accepted cache rows to the most
  recent 500 per conversation.
- Enforce canonical durable UUIDs, exact local sender/account ownership, bounded
  UTF-8 identifiers and content, positive accepted metadata, non-preceding reply
  sequences, account isolation, monotonic cursor advancement, WAL, foreign keys,
  and fail-closed future schema versions.
- Keep this repository detached from Widgets and WSS composition. The next
  application slice owns optimistic creation, retry policy, history application,
  and view-model projection.

## Consequences

Windows can restore reply intent and cached reply identity before connecting,
without changing the V1 database. A temporary duplication of V1 and V2 caches
is intentional during the compatibility window. Cached message text remains
local product data and must follow the future cache-clear/retention policy.

## Verification

The Qt and CMake repository test covers restart recovery, UTF-8 text, drafts,
reply identity, idempotency conflict, sender spoofing denial, account isolation,
failure/retry selection, ACK reconciliation, no accepted-state downgrade,
authoritative merge, monotonic cursors, absence of copied quote columns, and
future-schema rejection. It also covers atomic rejection of an unordered page,
mutation-only cursor advancement, and live-event merge without cursor movement.
Schema version 2 and application tests additionally cover recalled-target body
erasure, reply refusal, and deletion eviction before cursor commit.

## Rollback

Stop opening the detached V2 repository and remove its file after the preview
compatibility window. The V1 cache and server truth are unchanged; deleting the
V2 file loses only local cache/drafts/pending preview intent and must therefore
be a user-visible reset action once product wiring exists.
