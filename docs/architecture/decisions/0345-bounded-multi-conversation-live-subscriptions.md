# ADR-0345: Bounded Multi-conversation Live Subscriptions

- Status: Accepted
- Date: 2026-08-14
- Related milestone: M5
- Supersedes: ADR-0086

## Context

ADR-0086 deliberately allowed one active conversation per connection. A real
multi-conversation gateway measurement showed that reading a second authorized
conversation silently removed live delivery for the first. That behavior is not
suitable for a modern conversation list: a connected client must receive new
messages for multiple caught-up conversations without repeatedly switching the
server-side route. We still need bounded single-gateway evidence before choosing
Redis, a broker, or cross-gateway presence ownership.

## Decision

- Retain every authorized conversation subscription after its final history
  page instead of replacing the previous subscription.
- Bound one channel to 100 live conversation subscriptions. Reject an attempt
  beyond that bound before reading history and retain every existing route.
- Keep authorization and catch-up semantics unchanged: rejected or non-final
  history never creates a route, and a caught-up page plus subscription remains
  serialized with publication for that conversation.
- On socket close, authentication removal, or explicit unsubscribe, remove the
  channel from all conversation routes. Empty routes are deleted.
- Preserve sequence-based repair as the durable truth. Process-local routes are
  only live-delivery acceleration and remain reconstructable.
- Add a schema-7 production WSS/PostgreSQL scenario that distributes operations
  evenly across up to 100 GROUP conversations, records activation time, and
  reconciles every conversation independently.

## Consequences

A single authenticated connection can receive live events from a bounded set of
caught-up conversations, matching Web and Windows conversation-list behavior.
Memory now scales with active `(channel, conversation)` subscriptions rather
than only active channels, so the fixed bound and measured curves are required.
Clients with more than 100 active conversations still rely on incremental
history for conversations outside the live set until a future explicit
subscription protocol or account/device routing model is designed.

This does not establish cross-gateway delivery. Redis or a broker remains a
candidate only after multi-gateway ownership and failure evidence exists.

## Verification

Router tests require two retained authorized subscriptions, exact delivery from
both conversations, failed-history isolation, and cleanup of every route on
close. The schema-7 baseline requires exact membership/subscription counts,
even per-conversation durable sequences, all-peer publication counts, bounded
activation latency, and zero errors through the production TLS/WSS gateway.

## Rollback

Restore the single-conversation attribute and ADR-0086 replacement behavior.
No protocol field, durable row, migration, or client cache format changes, so
clients retain sequence-history recovery after rollback.
