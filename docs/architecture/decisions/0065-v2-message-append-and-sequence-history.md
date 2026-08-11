# ADR-0065: V2 Message Append and Sequence History

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The V2 schema already reserved conversation sequences, sender-scoped client
message IDs, payload hashes, and bounded payloads, but only schema constraints
were exercised. The application had no transport-independent messaging ports,
and retry races could not yet return one stable durable outcome or prove that a
rolled-back competitor did not consume a sequence.

## Decision

- Add transport-neutral application values/ports for authenticated message
  submission and bounded forward history. Own payload byte copies at module
  boundaries, limit payloads to 1 MiB, client-message IDs to 128 UTF-8 bytes,
  and page sizes to 1..100.
- Authorize every append inside its transaction against an enabled account, an
  active conversation membership, and an active device owned by that account.
  History requires the enabled active member. Return one opaque authorization
  denial for missing, left, revoked, disabled, or mismatched state.
- Allocate `conversation.next_sequence` and insert the message in one
  transaction. Let PostgreSQL `transaction_timestamp()` own `accepted_at` and
  return it with the stable application-generated message UUID.
- Scope idempotency to `(sender_account_id, client_message_id)`. An exact retry
  must match conversation, device, message type, payload hash, and payload bytes,
  then returns the original ID/sequence/time with `duplicate=true`. Any reuse
  with different material returns `IDEMPOTENCY_CONFLICT`.
- Resolve concurrent exact retries with `ON CONFLICT DO NOTHING`: the losing
  transaction rolls back its sequence increment before rereading the committed
  winner. Sequence gaps remain legal for deletion/administrative events, but an
  idempotency race does not create one.
- Read `afterSequence` in ascending order with `limit + 1`, return an explicit
  next cursor, current conversation high watermark, and `hasMore`. Deleted rows
  remain gaps and are not returned.

## Consequences

- PostgreSQL now implements the durable primitives needed by optimistic Web and
  Windows outboxes and reconnect synchronization without exposing SQL row shapes
  to transport code.
- Durable acceptance still does not mean delivery or read acknowledgement.
- No new V2 wire message types or gateway dispatch are enabled in this slice;
  protocol schemas and compatibility tests must precede exposure.

## Verification

Pure tests cover byte ownership and input bounds. The disposable PostgreSQL gate
uses two concurrent submissions to prove one original/one duplicate with the
same ID, sequence, and database timestamp; conflicting reuse is rejected, the
next distinct message receives sequence two, bounded pages advance correctly,
outsiders/left members cannot append or read, and the history query remains
eligible for the intended conversation/sequence index.

## Rollback

Remove the unused application ports and PostgreSQL adapter/tests. V001 remains
unchanged and V1 remains authoritative, so rollback requires no schema or
traffic action.
