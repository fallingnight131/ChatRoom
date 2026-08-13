# ADR-0388: In-Window PostgreSQL Pool Evidence

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0387 exposes the gateway-owned Hikari pool, while ADR-0386 samples the
authentication executor during the bounded dual-edge recovery window. Running a
second sampler would create a different observation window and double loopback
admin traffic. The existing sampler can extract both resource boundaries from
one metrics response.

## Decision

- Extend the existing five-millisecond reconnect sampler to capture PostgreSQL
  pool-metrics availability and maximum observed active connections, total
  connections, and threads awaiting a connection.
- Schedule sample starts on a five-millisecond target cadence. If collecting one
  loopback snapshot overruns its slot, start the next snapshot immediately
  rather than adding another five-millisecond delay; retain the actual sample
  count in evidence.
- Record the configured connection maximum and require it to stay constant for
  the whole run.
- Upgrade new evidence to schema version 3. Preserve schema versions 1 and 2 as
  read-only historical contracts and reject the PostgreSQL block in either
  earlier schema.
- Require every schema version 3 sample to have the Hikari management view
  available. Require at least one active and one total connection observation,
  and bind the configured maximum to the scenario's four-connection pool.
- Treat each Hikari gauge as independently sampled. Do not require active, idle,
  and total values to reconcile across concurrent transitions.

## Consequences

One bounded recovery curve can now show whether latency coincided with observed
authentication work, PostgreSQL connection use, or threads waiting for a pool
slot. The metric polling workload and five-millisecond interval remain part of
the benchmark identity.

This does not measure query latency, database server saturation, CPU, memory,
Netty event-loop lag, Redis latency, multi-host network effects, or a production
saturation knee.

## Verification

Contract tests retain schema versions 1 and 2 and cover missing, unavailable,
mismatched, malformed, and out-of-bounds schema version 3 pool evidence. The
real dual-edge harness must pass the strict validator before a clean exact-
revision result is committed.

## Rollback

Return new evidence to schema version 2 and stop recording the PostgreSQL block.
Runtime metrics from ADR-0387 remain useful. No product protocol, data model,
readiness, admission, or pool configuration changes.
