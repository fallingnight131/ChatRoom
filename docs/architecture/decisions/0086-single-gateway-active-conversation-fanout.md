# ADR-0086: Single-gateway Active-conversation Fan-out

- Status: Superseded by ADR-0345
- Date: 2026-08-12
- Related milestone: M3

## Context

Type 104 and Web reconciliation exist, but no gateway publishes the event. M3
needs a correct single-instance live path before M5 adds Redis and multi-gateway
routing. Subscribing before authorization could leak messages; subscribing after
a history query without coordination creates a race where a committed message
falls between the snapshot and the live stream.

## Decision

- Add one process-local conversation router owned by the gateway listener and
  shared by every upgraded connection in that process.
- A channel has at most one active conversation subscription. Replace it only
  after an authorized final history page (`has_more=false`); denial, switching,
  disconnect, and channel close remove the previous subscription.
- Serialize the final history query and subscription with publication for that
  conversation. A commit racing the snapshot therefore appears either in the
  page or as a later live event. Earlier history pages do not subscribe.
- Publish type 104 only for a new durable acceptance, after writing the correlated
  type 101 ACK. Never publish an idempotent duplicate. Build event identity from
  the server-bound submission and database-authoritative acceptance result.
- Bind each event envelope to the receiving channel's server-side session.
  Drop closed/unauthenticated channels. Close an unwritable subscriber so its
  reconnect history path repairs the gap instead of growing an unbounded queue.
- Keep route state bounded by active channels and remove empty routes, including
  exceptional history paths. Export fixed-cardinality published and
  slow-consumer-close counters without account or conversation labels.
- Treat this as single-gateway preview behavior. M5 must replace/extend routing
  for multiple gateways. Any future V2 membership mutation must invalidate live
  subscriptions before that mutation can be enabled.

## Consequences

Two preview clients on one gateway can receive low-latency active-conversation
messages while retaining sequence-history recovery. The final history query is
serialized with local publication for correctness, which may create per-room
contention and requires benchmark evidence before production. Inactive
conversations rely on directory/history synchronization; this is not device
delivery or read acknowledgement.

## Verification

Router tests cover authorization, final-page gating, session-bound event shape,
conversation replacement, disconnect cleanup, exceptional cleanup, and no
publication without a subscriber. Handler tests prove ACK-before-event and no
event for duplicate acceptance. Gateway/admin tests verify shared composition
and fixed-cardinality metrics. The full Java workspace and Web gates remain
required.

## Rollback

Inject the no-op router or stop type 104 publication. Durable append, ACK,
history, protocol numbering, PostgreSQL data, and V1 remain unchanged.
