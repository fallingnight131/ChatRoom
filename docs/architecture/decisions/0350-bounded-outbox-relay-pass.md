# ADR-0350: Bounded Outbox Relay Pass

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0349 provides fenced PostgreSQL outbox leases, but directly embedding claim,
publication, retry, and completion policy in a timer or Redis adapter would mix
application lifecycle with infrastructure. It would also make failure labels and
backoff inconsistent across tests and future runtime compositions.

This slice needs a deterministic unit of work before any scheduler or Redis
client is activated. The current process-local router must remain authoritative.

## Decision

- Introduce a scheduler-neutral `runOnce` application service. Each pass claims
  at most 100 items under a reviewed one-second-to-five-minute lease.
- Validate that persistence did not exceed the requested batch and returned
  unique event/claim IDs for the exact owner, claim time, and expiry.
- Publish sequentially within the bounded pass. Publication adapters return one
  of three fixed outcomes: published, dependency unavailable, or dependency
  rejected. An unexpected runtime exception maps to the fixed
  `PUBLISHER_FAILURE` code; exception text is never persisted or exposed as a
  metric label.
- Complete success through the fenced outbox token. Defer failure with
  exponential retry starting no lower than 100 ms and capped at five minutes.
  A completion after lease expiry or rejected by the fenced port is counted as
  lost ownership and never retried using the stale token.
- Return only fixed-cardinality pass counts: claimed, published, deferred,
  ownership lost, and unexpected publisher failures. Runtime metrics will
  consume these counts in a later composition slice.

## Consequences

Schedulers and Redis adapters can remain replaceable infrastructure. Unit tests
can deterministically prove retry and stale-ownership behavior without starting
threads or dependencies. Sequential publication bounds concurrency and keeps
the first activation conservative; measured backlog may justify controlled
parallelism through a later decision.

This service alone delivers nothing because no product runtime constructs or
schedules it. Outbox age/count gauges, a lifecycle loop, authoritative event
loading, and Redis publication remain required before activation.

## Verification

Application tests prove fixed outcome mapping, exception redaction, capped
exponential backoff, fenced completion loss, duplicate-claim rejection, and
reviewed configuration bounds. The full Java backend check remains the release
gate for this inactive slice.

## Rollback

Leave the service uncomposed or remove it. PostgreSQL outbox rows and the current
single-gateway local publication path are unchanged.
