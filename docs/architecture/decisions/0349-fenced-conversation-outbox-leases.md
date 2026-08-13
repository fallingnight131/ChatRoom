# ADR-0349: Fenced Conversation Outbox Leases

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

V050 atomically records new V2 message events in the PostgreSQL conversation
outbox, but it does not define how concurrent relay workers own work or recover
after failure. Using only a stable worker or gateway identity as the owner is
not sufficient: after a lease expires, a delayed callback from the old attempt
could otherwise acknowledge a newer attempt made by the same process identity.

The relay must preserve conversation order, remain bounded, tolerate duplicate
publication, and expose no message body or user identity in its work record.
This decision defines the inactive persistence port only. It does not start a
scheduler, connect Redis, or replace the process-local live router.

## Decision

- Add an independent random `claim_id` to every claim. Mutation requires the
  exact event ID, owner, claim ID, and lease expiry, fencing every expired or
  superseded attempt even when an owner identity is reused.
- Claim at most 100 events for a lease from one second through five minutes.
  PostgreSQL locks candidates with `FOR UPDATE SKIP LOCKED`; each conversation
  contributes only its earliest unpublished sequence.
- A later sequence remains blocked while any earlier sequence is unpublished,
  including while the earlier event is delayed after failure. This deliberately
  favors order over bypassing a poison event.
- Claim is available when `available_at` has arrived and no live claim exists.
  An expired lease may be reclaimed, receives a fresh claim ID, and increments
  the durable attempt count.
- Success marks publication and clears all claim/failure state. Failure records
  only a bounded uppercase infrastructure code, sets a caller-selected future
  retry time, and releases the claim. Both transitions require the exact fenced
  lease and are safe to repeat as a false/no-op result.
- The claim contains only event/conversation IDs, sequence, fencing metadata,
  and attempt count. A future publisher must load authoritative event content
  through the conversation-history boundary.

## Consequences

Multiple workers can claim independent conversation heads without publishing a
later event ahead of an earlier unpublished one. A crashed or stalled worker
cannot permanently own work, and stale completion cannot mutate a replacement
lease. Delivery remains at least once: publication followed by an acknowledgement
failure can cause a duplicate, which downstream gateways must deduplicate and
repair by conversation sequence.

One blocked event blocks its conversation until retry or operator action. Relay
age, unpublished count, attempt count, and bounded failure-code metrics are
required before activation; dead-letter or skip policy requires a later ADR
because it changes visible ordering and recovery semantics.

## Verification

The real PostgreSQL gate migrates V001 through V051 and proves bounded claiming,
one head per conversation, delayed retry, expired-lease reclamation, monotonic
attempt count, stale-token fencing, wrong-owner rejection, idempotent completion,
and next-sequence release only after the head is published.

## Rollback

Do not compose or schedule the inactive adapter. Retain the additive nullable
claim ID and outbox rows; the existing single-gateway local router continues to
publish live messages. Schema contraction requires a later compatibility-window
decision.
