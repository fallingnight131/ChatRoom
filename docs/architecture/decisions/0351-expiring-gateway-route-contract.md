# ADR-0351: Expiring Gateway Route Contract

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The outbox relay lifecycle is ready to call a publication adapter, but Redis
must not become durable message truth or an authorization authority. A direct
Redis API in messaging code would spread key layout, expiry, target limits, and
partial-publication behavior across the gateway.

## Decision

- Represent each gateway by a random boot UUID with a lease no longer than one
  minute. Conversation routes use the same maximum expiry and carry the last
  sequence caught up before route publication.
- Publish a conversation route only after authoritative membership validation
  and history catch-up. After publication the gateway must perform the second
  sequence repair required by ADR-0348; the route itself grants no access.
- Resolve no more than 64 target gateways for the first M5 deployment. Target
  lookup reports whether the result is complete. Never publish a partial page;
  treat truncation as a retryable/review-required rejection.
- Append only target gateway UUID, stable event UUID, conversation UUID, and
  conversation sequence to a bounded per-gateway stream. Message content and
  identity data remain in PostgreSQL and are loaded through authoritative
  history by the consumer.
- Bound each target stream to 100 through 100,000 entries. Any target failure
  makes the outbox attempt fail as a whole. Already-appended hints may repeat on
  retry and consumers must deduplicate/repair by event identity and sequence.
- An empty complete target set is successful: durable history already contains
  the event and no live gateway currently needs a hint.

## Consequences

The application depends on small route and stream ports rather than Redis key
commands. A Redis adapter can be capability-tested and replaced without changing
message semantics. A partial multi-target attempt intentionally produces
duplicates instead of silently omitting a gateway.

The initial 64-gateway ceiling is an explicit deployment envelope, not a scale
claim. Exceeding it blocks publication and requires partition/paging evidence
and a later ADR. Route renewal, consumer acknowledgement, second repair, and
the concrete Redis key/stream implementation remain incomplete.

## Verification

Application tests prove complete multi-target publication, stable payload-free
hints, bounded stream configuration, no publication for incomplete target
lookup, and aggregate retry on dependency failure. Real Redis expiry, trimming,
duplicate, restart, TLS, and authentication tests remain required for the next
adapter slice.

## Rollback

Leave the ports unimplemented/uncomposed and retain the process-local live
router. No schema, protocol, or product traffic changes in this decision.
