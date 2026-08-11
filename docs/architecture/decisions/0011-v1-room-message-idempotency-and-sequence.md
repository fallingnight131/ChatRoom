# ADR-0011: V1 Room-Message Idempotency and Sequence Resume

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

The V1 room-text path accepted a fire-and-forget `CHAT_MSG`, inserted a new
SQLite row on every receipt, and then broadcast it. A lost connection after the
insert but before the sender observed its own broadcast left the client unable
to distinguish failure from success. Retrying could create another durable row.
History used timestamp pagination, so a reconnect could not express a precise
server-owned resume position.

The target model requires a client-generated idempotency key, stable server
message ID, per-conversation sequence, durable acceptance, and cursor-based
resume. V1 Qt and Web clients must continue to work while this behavior is
introduced incrementally. Direct messages, attachment submission, and replay of
non-message events are separate follow-up slices.

## Decision

Introduce an additive V1-compatible reliability extension for room text and
emoji messages:

- Updated clients send `data.clientMessageId`, limited to 128 UTF-8 bytes. For
  an older client that omits it, the server uses the existing envelope `id` as
  the compatibility key. An absent/invalid key is rejected.
- SQLite owns the accepted result. `messages` gains nullable
  `client_message_id` and `sequence`; `(user_id, client_message_id)` is unique
  for non-empty keys and `(room_id, sequence)` is unique for assigned sequences.
- `room_message_sequences` stores the durable high watermark for each room.
  Sequence allocation and message insertion occur in one SQLite transaction.
  The high watermark never moves backwards when messages are physically
  deleted or the server restarts.
- Repeating the same sender/key/room/content/type returns the original message
  ID, sequence, and timestamp without inserting or broadcasting again. Reusing
  a key for a different command returns `CLIENT_MESSAGE_ID_CONFLICT`.
- A focused `RoomMessageService` owns membership/input/idempotency decisions.
  `ChatServer` remains a V1 transport adapter and `DatabaseManager` remains the
  only SQL owner.
- Every submission receives `CHAT_SEND_RSP`. `success: true` means the message
  is durably accepted; `duplicate: true` means the same already-accepted result
  was recovered. This does not mean recipient delivery or read.
- Committed `CHAT_MSG` and room history add optional `clientMessageId` and
  `sequence`. Sender identity, message ID, sequence, and timestamp remain
  server-authoritative.
- `HISTORY_REQ.data.afterSequence` selects sequence-resume mode. The response
  returns ordered messages plus `nextSequence`, `lastSequence`, and `hasMore`.
  `nextSequence` is the only resume cursor; clients must not infer a missing
  message from numeric gaps because administration may physically delete rows.
  A final page advances the cursor to the durable high watermark even when a
  deleted row creates a gap.
- Qt and Web message collections deduplicate by stable server ID, with
  `clientMessageId` as an additional Web reconciliation key. Send rejection is
  surfaced to the user. Durable optimistic state and automatic retry belong to
  M2 local repositories/outboxes.
- Structured, sampled server logs expose accepted, duplicate, and rejected
  cumulative totals without content or client-generated identifiers.
- Invalid oversized client identifiers are not reflected in responses, avoiding
  a request-to-response amplification path.

All sequence values are JSON numbers and must remain within the exact integer
range supported by JSON consumers. V2 generated schemas will use an explicit
64-bit integer representation.

## Alternatives Considered

- Treat the existing global autoincrement `messages.id` as a room cursor:
  rejected because it is not a per-conversation sequence and couples clients to
  a SQLite implementation detail.
- Keep only an in-memory duplicate cache: rejected because retry correctness
  must survive reconnect and process restart.
- Re-broadcast the original message on every duplicate request: rejected
  because it amplifies retries and forces every old recipient to deduplicate.
- Require all clients to upgrade before accepting messages: rejected because it
  breaks the documented V1 compatibility window.
- Implement room, direct, file, event replay, and local outboxes together:
  rejected as an unsafe migration-sized batch. The room-text slice proves the
  storage and protocol guarantees first.

## Consequences

Room text/emoji retries are safe across connections and process restarts, and a
member can request a bounded ordered range after a known sequence. Old clients
continue to send and receive messages because all request additions are optional
and unknown response types are ignored.

SQLite still serializes persistence on the current central application path.
The new guarantee does not provide exactly-once transport, recipient delivery,
read acknowledgement, durable client outboxes, direct-message idempotency, file
submission idempotency, or replay of recalls/deletions. Those limitations must
not be hidden by the word “accepted.”

## Migration and Rollback

Startup expands the nullable columns, linearly scans only rows whose sequence is
missing, assigns deterministic per-room values ordered by existing message ID,
then creates/raises durable room high-watermark rows and unique indexes. The
backfill is restart-safe and never rewrites an assigned sequence.

Rolling back to the previous server binary is schema-compatible: it ignores the
new table/columns and may append rows with a null sequence. Returning to the new
binary backfills only those rows. Do not drop the columns, indexes, or sequence
table during the M1/V1 compatibility window. A database backup remains required
before production migration.

## Verification

`Tests/v1_room_message_reliability_test.py` launches real server processes and
proves:

- first acceptance returns the same ID/sequence as broadcast;
- exact retries do not insert or rebroadcast and conflicting reuse is rejected;
- an old-client envelope ID is a compatible idempotency fallback;
- a non-member receives an explicit authorization rejection;
- bounded sequence pages return ordered messages and deterministic cursors;
- idempotency survives a server restart;
- restart completes a deliberately interrupted nullable-sequence migration;
- deleting the highest message cannot make a later migration reuse its sequence;
- structured outcome monitoring distinguishes accepted, duplicate, and rejected
  submissions.

Database schema verification requires the new columns/table and asserts that
the sequence-resume query uses `idx_messages_room_sequence`. The full V1, Qt,
Web, schema, and password-migration gates remain required before commit.
