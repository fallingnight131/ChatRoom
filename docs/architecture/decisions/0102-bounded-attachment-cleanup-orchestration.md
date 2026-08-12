# ADR-0102: Bounded Attachment Cleanup Orchestration

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

ADR-0101 makes cleanup work durable, but a worker also needs strict batch and age
bounds, failure isolation, and fixed outcome counters. A provider outage must not
mark objects deleted or stop all later candidates in the same pass.

## Decision

- Add transport- and scheduler-neutral application ports for durable cleanup
  state and idempotent object deletion.
- Run one bounded pass at a time: revoke pending rows older than a configured
  age, page the durable cleanup-required set, delete each object, then confirm
  successful deletion in SQL.
- Bound pending age to 10 minutes through 30 days and one pass to at most 1,000
  candidates. Reject a persistence adapter that returns more than requested.
- Isolate each provider failure and continue later candidates. Do not confirm a
  failed delete. Treat a failed SQL confirmation separately; the durable row
  remains eligible even if the provider delete already succeeded, so retry
  requires idempotent provider deletion.
- Return only fixed counters: revoked, attempted, deleted, provider failures,
  and confirmation failures. Do not label reports with attachment, account,
  conversation, device, object key, filename, or endpoint.
- Keep orchestration inactive until PostgreSQL and S3 deletion adapters,
  scheduling/backoff, metrics export, and provider acceptance exist.

## Consequences

The eventual scheduler can retry safely after provider, database, or process
failure without embedding scheduling or cloud SDK types in the application
core. The current service catches provider runtime failures intentionally; the
adapter and metrics layer must provide safe aggregate diagnostics without
signed URLs or object keys.

## Verification and Rollback

Application tests prove cutoff/batch forwarding, revoke-delete-confirm order,
successful counters, provider and confirmation failure isolation, policy bounds,
and rejection of an overproducing persistence adapter.

Rollback removes the inactive ports, service, report, and tests. It does not
alter V013, runtime composition, provider objects, protocol, or user traffic.
