# ADR-0353: Payload-Free Gateway Stream Consumption

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The Redis adapter can append bounded per-gateway hints, but a gateway needs a
bounded read contract that does not mistake Redis stream position for durable
message progress. Trimming, Redis restart, partial publication, and duplicate
outbox attempts can all remove or repeat hints.

## Decision

- Read only the stream named by the gateway's random boot UUID, after an opaque
  Redis stream ID, in batches of 1 through 1,000 entries.
- Accept exactly three fields: event UUID, conversation UUID, and positive
  conversation sequence. Reject malformed or extra fields rather than silently
  extending the trust boundary.
- Treat the Redis stream ID only as a position for the current ephemeral stream.
  It must never advance a durable client cursor or prove message continuity.
- Before local delivery, a later consumer service must match an authorized local
  subscription and compare conversation sequence. Duplicate/old hints are
  ignored; a gap or trimmed/restarted stream triggers PostgreSQL history repair.
- An empty batch preserves the requested stream ID. Restarting with a new gateway
  boot UUID starts a new stream and relies on the required catch-up/register/
  second-repair sequence rather than recovering the old stream.

## Consequences

Redis loss or trimming cannot corrupt durable progress. Strict hint parsing
prevents a Redis value from smuggling message bodies or identity claims into the
gateway. Product activation still requires the local-subscription consumer,
bounded polling lifecycle, repair tests, and graceful old-stream cleanup.

Non-blocking polling is used in the first adapter so the existing shared Lettuce
connection is never occupied by a blocking command. A dedicated blocking
connection or async API may be introduced later based on latency/CPU evidence.

## Verification

Real Redis tests append 150 hints into an exact 100-entry stream, then prove the
retained sequence begins at 51, bounded 60/40 paging, cursor advancement, empty
tail behavior, close/reconnect, and expiry cleanup. Compilation keeps the
Lettuce generic-varargs warning suppression scoped only to the XREAD method.

## Rollback

Leave the consume port and adapter method uncomposed. The process-local live
router and PostgreSQL history path remain authoritative.
