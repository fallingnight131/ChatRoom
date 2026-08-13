# ADR-0355: Fail-Closed Hint Consumer Cursor

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The Redis adapter returns bounded ordered hints, but advancing its ephemeral
cursor before local authorization and PostgreSQL repair succeeds could silently
skip a failed event. Treating every hint as deliverable would also bypass local
subscription and current membership checks.

## Decision

- Process at most 1,000 hints sequentially per pass and require every hint to
  target the current gateway boot UUID.
- Delegate each hint to a local authoritative repair port, which classifies it
  as applied, duplicate, or no active authorized subscription.
- Advance the Redis cursor after any of those three completed classifications.
  They are terminal for this boot stream: applied/duplicate converged, while no
  subscription has no current live recipient and PostgreSQL remains durable.
- On the first repair exception or null/invalid result, stop immediately. Count
  that entry as failed but return the cursor of the preceding successful entry,
  so the failed hint is read again.
- Reject oversized, wrong-target, or mismatched-cursor batches as adapter defects
  rather than partially processing them.

## Consequences

Dependency failures cannot move the ephemeral read position past unprocessed
work. One poison hint blocks later hints until strict parsing/repair succeeds or
the boot stream expires; skipping it requires a later explicit operational
decision. Metrics have fixed applied/duplicate/not-subscribed/failed outcomes.

The local Netty router still needs to implement the repair port with per-channel
contiguous sequence state, renewed PostgreSQL authorization, bounded history,
and slow-consumer handling before product composition.

## Verification

Application tests prove ordered terminal classifications, stop-at-first-failure,
cursor retention immediately before the failed entry, wrong-target rejection,
and the 1,000-entry configuration bound.

## Rollback

Leave the service uncomposed. Redis streams, PostgreSQL history, and the current
single-gateway live route are unchanged.
