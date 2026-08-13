# ADR-0354: Route Registration Second Repair

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

A gateway that reads history and then publishes a Redis route has a race: an
event can commit after the last history page but before the route becomes
visible, so neither the first catch-up nor live routing is guaranteed to deliver
it. Publishing a route before catch-up would create the inverse problem and may
expose live hints before local authorization/subscription is ready.

## Decision

- Give every process boot a random gateway UUID and a 5–60 second renewable
  lease. Route publication is possible only while that boot lease exists.
- Register a conversation only after authoritative catch-up has returned a
  contiguous sequence. Immediately after route publication, run authoritative
  repair again from that exact sequence.
- Report registration success only after the second repair finishes without
  moving progress backwards. If repair throws or violates monotonic progress,
  remove the visible route before propagating failure.
- A route-publication rejection runs no repair and returns an empty result.
  Callers retain the existing local subscription/history behavior and may retry.
- Reference counting multiple local subscribers and scheduling lease renewal
  belong to the gateway composition layer; the application service exposes
  explicit renew, remove-conversation, and release-gateway operations.

## Consequences

The catch-up/register/repair window closes without making Redis authoritative.
Failures fail closed by removing the route. A Redis route may briefly exist
while second repair is running, but any arriving hint is still gated by local
authorized subscription and sequence logic in the future consumer.

Product activation still requires local conversation reference counting,
periodic renewal before half-life, lease-loss behavior, consumer polling, and a
two-gateway real-dependency test.

## Verification

Application tests prove boot lease expiry, ordered publication then repair,
monotonic repaired progress, no repair after route rejection, and route removal
after repair exception/backwards progress. Full backend `check` covers all
dependent modules.

## Rollback

Leave the service uncomposed. Existing process-local subscription and history
flows remain unchanged.
