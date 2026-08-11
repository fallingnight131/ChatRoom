# ADR-0068: Isolated Messaging Workers

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3
- Updates: ADR-0067

## Context

The first V2 messaging dispatcher safely reused the existing bounded
authentication executor while the route remained inactive. Password hashing,
session persistence, message append, and history reads have different cost and
availability characteristics. Sharing one queue would allow a burst of database
message work to delay every login and reconnect attempt.

## Decision

- Give messaging a gateway-owned fixed worker pool and bounded FIFO queue that
  is separate from authentication workers and Netty event loops.
- Configure it independently with `CHATROOM_GATEWAY_MESSAGING_WORKERS` (default
  4, range 1..64) and `CHATROOM_GATEWAY_MESSAGING_QUEUE_CAPACITY` (default 512,
  range 1..100000). These defaults are safety bounds, not capacity claims.
- Name its threads `chat-message-*`, reject saturated submissions immediately,
  and preserve the dispatcher's existing retryable `RATE_LIMITED` response.
- Construct both worker pools before listener startup and close messaging then
  authentication workers during reverse-order runtime shutdown.

## Consequences

- Slow message persistence/history cannot consume authentication execution
  slots, though both workloads still intentionally share the bounded PostgreSQL
  connection pool.
- Worker and database-pool sizes must be tuned together from reproducible load
  measurements. Increasing threads alone cannot increase database capacity.
- Queue/active gauges and message outcome telemetry remain a separate
  observability slice before product cutover.

## Verification

Unit tests prove the messaging worker and queue bounds, deterministic saturation,
thread naming, unsafe configuration rejection, lifecycle closure, strict runtime
configuration defaults, and reverse shutdown order. Messaging-handler tests
prove the supplied executor controls admission and saturation behavior.

## Rollback

Remove the separate pool/configuration and temporarily pass the authentication
executor to the messaging handler. This changes resource isolation only and does
not alter the wire or durable data contract.
