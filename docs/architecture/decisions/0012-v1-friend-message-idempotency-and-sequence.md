# ADR-0012: V1 Friend-Message Idempotency and Sequence Resume

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

ADR-0011 made room text/emoji submission retry-safe, but direct text/emoji
messages still used fire-and-forget insertion and timestamp-only history. A
sender could therefore create duplicates after an ambiguous disconnect, and a
reconnecting participant had no precise server-owned resume cursor.

## Decision

Apply the ADR-0011 model to V1 friend text/emoji messages as an additive
extension:

- updated clients send a 1–128 byte `clientMessageId`; an old client falls back
  to its existing envelope `id`;
- `FriendMessageService` resolves the authenticated sender's friendship,
  validates the command, and owns retry/conflict decisions;
- `friend_messages` stores nullable `client_message_id` and a per-friendship
  `sequence`; allocation and insertion share one SQLite transaction;
- `friendship_message_sequences` is the durable high watermark and never moves
  backwards after deletion;
- `(sender_id, client_message_id)` and `(friendship_id, sequence)` are unique for
  assigned values;
- exact retries return the original result through `FRIEND_CHAT_SEND_RSP` and
  are not broadcast again; conflicting reuse returns
  `CLIENT_MESSAGE_ID_CONFLICT`;
- non-existent users and missing friendships share
  `FRIENDSHIP_ACCESS_DENIED`, avoiding relationship discovery through error
  detail;
- direct history accepts `afterSequence` and returns ordered messages,
  `nextSequence`, `lastSequence`, and `hasMore`; legacy timestamp history remains
  compatible;
- Qt and Web direct-message collections consume stable IDs and sequences and
  deduplicate live/history overlap.

Success acknowledges durable acceptance only. It does not assert recipient
delivery or read. V1 room and friend messages remain in separate tables, so the
same sender key is unique within each path rather than globally; V2's unified
conversation message model will enforce one namespace.

## Consequences

Direct text/emoji retry and reconnect recovery now have the same guarantees as
room text/emoji, including across process restart and partial migration. The
additional response type is ignored by old clients. Direct attachments,
recall/delete event replay, durable client outboxes, and multi-device state
remain follow-up work.

## Migration and Rollback

Startup adds nullable columns, backfills only missing sequences in existing-ID
order, preserves any higher durable watermark, and then creates the unique
indexes. Old binaries ignore these additions and may append null sequences;
returning to the new binary resumes the backfill. Do not contract the schema
during the V1 compatibility window.

## Verification

`Tests/v1_friend_message_reliability_test.py` proves first acceptance, exact
retry, conflicting reuse, legacy-envelope compatibility, oversized-key
rejection without reflection, non-friend denial, ordered resume pages, restart
durability, interrupted backfill, non-reused deleted high watermarks, and
redacted structured outcome logs.
