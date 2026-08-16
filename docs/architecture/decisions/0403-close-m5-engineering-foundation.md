# ADR-0403: Close the M5 Engineering Foundation

- Status: Accepted
- Date: 2026-08-16
- Owners: project maintainers
- Related milestone: M5

## Context

The M5 roadmap ended with an early planning list even though the recorded M5
work now includes the transactional outbox, fenced relay, expiring Redis
routes, bounded per-gateway Streams, authorized PostgreSQL repair, multiple
product gateways, HAProxy withdrawal/crash/reload drills, mixed-version
rollout, dual-edge recovery, and repeated reconnect evidence.

Leaving those old bullets as unfinished work makes the roadmap contradict its
own progress log. Calling the local reconnect ladder a capacity result would be
equally inaccurate: it is a bounded diagnostic on a documented development
host, not a production SLO or safe fleet size.

## Decision

- Treat the M5 engineering foundation as complete. Distributed routing remains
  default-off and must pass environment-specific release and operations gates
  before production activation.
- Map the M5 exit evidence explicitly:
  - ADR-0373 proves that an abruptly lost gateway does not erase committed
    PostgreSQL messages and that a resumed session repairs history;
  - ADR-0369 proves cross-gateway delivery through payload-free Redis hints and
    authoritative PostgreSQL reads;
  - ADR-0358 and ADR-0365 prove repeated/racing hints do not duplicate visible
    socket delivery;
  - ADR-0368 and ADR-0370 through ADR-0402 document bounded dependency,
    rollout, edge, reconnect, and resource-observation envelopes.
- Describe the measured load result only as a bounded diagnostic envelope. It
  does not establish production capacity.
- Keep an independent durable broker deferred until sustained relay backlog or
  asynchronous-worker evidence justifies the dependency.
- Keep database partitioning and read replicas deferred until production query
  evidence identifies a concrete bottleneck.
- Introduce push, thumbnail, scanning, retention, audit, or analytics workers
  as feature-owned vertical slices. They are not prerequisites for closing the
  messaging scale foundation.

## Consequences

The roadmap can advance without implying that distributed routing is already
enabled in production. PostgreSQL remains durable truth; Redis remains
reconstructable routing infrastructure; per-conversation ordering remains the
outbox and repair partition boundary.

Every deployment still owns Redis TLS/ACL configuration, secret distribution,
load-balancer health policy, alert thresholds, rollback rehearsal, and a
representative load test. A future broker, database partition, or worker
service changes an operational boundary and therefore requires its own ADR.

## Verification

- Validate all retained M5 evidence with its versioned strict validator.
- Run the ordinary Java workspace checks after any distributed-routing change.
- Re-run the relevant real PostgreSQL/Redis/TLS-WSS/HAProxy gate for each
  release topology or compatible release pair.
- Never translate the local repeated ladder into a production capacity claim.

## Rollback

Reopen M5 if an exit-evidence defect invalidates one of the four mappings. Keep
distributed routing default-off while the defect is corrected; no protocol or
persistent schema rollback is required by this documentation decision.
