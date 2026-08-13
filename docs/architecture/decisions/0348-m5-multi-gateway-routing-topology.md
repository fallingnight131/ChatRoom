# ADR-0348: M5 Multi-Gateway Routing Topology

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The Java gateway now has reproducible evidence for 100 active conversations,
40-receiver fan-out, controlled reconnect arrivals, real slow-consumer closure
and repair, PostgreSQL pool saturation, and complete PostgreSQL stop/start
recovery. Those bounded single-host results do not show a throughput need for a
large independent broker. They do show that the current process-local router
cannot satisfy the M5 availability requirement: clients attached to different
gateways cannot receive one another's live events, and losing the only gateway
disconnects every session.

PostgreSQL already owns durable message identity and conversation sequence.
Clients already repair gaps by last contiguous sequence and deduplicate
at-least-once delivery. Distributed infrastructure must preserve those
properties instead of becoming a second source of message truth.

Affected quality attributes are availability, ordering, recoverability,
operability, latency, and deployment complexity. This decision defines the
target boundary and implementation order; it does not activate Redis, multiple
listeners, or product traffic by itself.

## Decision

### Durable publication boundary

- Add a PostgreSQL transactional outbox row in the same transaction that first
  commits a conversation event. The outbox identity is stable and its partition
  key is the canonical conversation UUID; its sequence is the server-assigned
  conversation sequence.
- Exact idempotent retries create no second outbox row. Relay retries are
  at-least-once and consumers must deduplicate by stable event identity plus
  conversation sequence.
- PostgreSQL messages/events remain the only durable truth. No Redis value or
  broker payload may authorize membership, replace history, or advance a client
  cursor without the existing sequence checks.

### Reconstructable Redis routing

- Give every gateway instance a random boot identity and a short renewable
  lease. Store device/gateway and conversation/gateway route membership with
  expiries; stale entries are ignored and removable without data migration.
- Establish a route only after authorized history catch-up, then perform a
  second sequence repair after route publication. This closes the race between
  the final history page and distributed route visibility.
- Dispatch committed outbox events to bounded per-gateway Redis Streams for the
  currently leased target gateways. A gateway consumes only its own stream and
  applies events only to matching local subscriptions.
- Redis Streams are live-delivery hints, not durable message storage. Streams
  have bounded retention; a missing, duplicate, stale, or out-of-order hint
  triggers PostgreSQL sequence repair. Periodic bounded repair protects active
  sessions from a lost hint or Redis restart.

### Deployment phases

1. Add the inactive outbox schema, application port, PostgreSQL write, relay
   claim/retry rules, and metrics while the single-gateway direct publish path
   remains authoritative.
2. Add an inactive Redis adapter with strict TLS/authentication, bounded pools,
   lease renewal, route expiry, stream limits, failure backoff, and a real-Redis
   capability test.
3. Run two gateways on loopback behind a test load balancer. Require cross-node
   live delivery, duplicate suppression, Redis loss/restart repair, gateway loss
   repair, bounded drain, and continuous PostgreSQL sequence reconciliation.
4. Enable distributed routing behind an immutable deployment flag. Roll back
   gateway consumers first; retain outbox rows/schema and sequence history.

Do not introduce Kafka, RocketMQ, Pulsar, or another independent durable broker
in this phase. Redis Streams plus PostgreSQL outbox solve the first measured
multi-gateway gap with one new operational dependency. Background scanning,
push, thumbnails, retention, audit export, or sustained relay backlog may later
justify a dedicated broker through a separate ADR and operational benchmark.

## Consequences

The topology can scale gateway connections horizontally without moving durable
truth out of PostgreSQL. Redis loss degrades live fan-out but not message commit
or recovery. A gateway processes only targeted streams rather than every event
in the system.

The design adds outbox retention/relay operations, Redis lease renewal, route
races, stream trimming, duplicate delivery, and repair traffic. Readiness must
distinguish whether a deployment is still in single-gateway mode or requires
Redis for new distributed sessions. Operators must monitor unpublished outbox
age/count, lease renewal failures, stream lag/trim, duplicate hints, gap repair,
and cross-node publication latency without identity labels.

Redis Cluster hash tags and stream partition counts are deliberately deferred
until a two-gateway baseline and retained workload distribution exist. The
conversation UUID remains the stable partition key regardless of physical
partition count.

## Verification

- V050 clean migration, restart, constraints, outbox idempotency, claim expiry,
  retry, and retention tests;
- real PostgreSQL proves message/event plus outbox atomicity under concurrent
  exact retry and rollback;
- real Redis capability tests prove TLS/auth, lease expiry, bounded stream
  retention, retry, duplicate delivery, and restart behavior;
- two real gateways prove same- and cross-node delivery, per-conversation order,
  gap repair, Redis outage, one-gateway loss, rolling drain, and no committed
  message loss;
- no capacity claim is made until the distributed scenario has a clean,
  commit-exact baseline.

## Rollback

Keep the additive outbox table and stop distributed consumers/relay through the
immutable deployment flag. Return traffic to one gateway and its current local
router. Unpublished outbox rows remain inspectable and retryable; PostgreSQL
history continues to repair clients. Contracting the schema requires a later
compatibility-window ADR, not emergency rollback.

