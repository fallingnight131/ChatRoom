# ADR-0069: V2 Messaging Telemetry

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3
- Updates: ADR-0068

## Context

The authenticated messaging path and its isolated worker pool had bounded
failure behavior but no production-readable signal. Operators could not
distinguish durable acceptance, duplicates, authorization denials, conflicts,
worker saturation, or unexpected application failures, and could not see worker
pressure when choosing safe deployment sizes.

## Decision

- Count only fixed messaging outcomes: accepted, duplicate, history page,
  denied, idempotency conflict, saturated, and failed.
- Export those counters plus current message-worker active count and queue size
  through the existing exact-path loopback `/metrics` endpoint.
- Never label metrics with account, device, peer, session, request, conversation,
  or message identifiers. The label vocabulary is closed in code.
- Record outcomes at the gateway/application boundary: durable result mapping,
  bounded executor rejection, and normalized unexpected failure. Structural
  protocol rejection remains protocol telemetry work rather than a message
  database outcome.

## Consequences

- Operators can alert on saturation/failure and tune worker/database capacity
  without high-cardinality or identity-bearing metrics.
- Counters describe outcomes, not end-to-end delivery or latency. Histograms,
  alert thresholds, and capacity claims require a later reproducible load slice.

## Verification

Handler tests verify durable duplicate and denial recording without identifiers.
Loopback admin tests verify the fixed Prometheus series and worker gauges appear
beside authentication metrics while retaining hardened response behavior.

## Rollback

Remove message series from the admin renderer and pass the no-op event sink. The
message protocol, persistence, and saturation behavior remain unchanged.
