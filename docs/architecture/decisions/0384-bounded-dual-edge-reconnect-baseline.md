# ADR-0384: Bounded Dual-Edge Reconnect Baseline

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0374 measures six sessions moving to a surviving gateway after another
gateway JVM dies behind one HAProxy. ADR-0381 proves one session can move after
an entire edge process dies. Neither measures the concentrated arrival curve at
an already-loaded secondary edge and gateway after primary-edge loss.

This repository still has no production multi-host environment or product-fleet
reconnect trace. A bounded development-host curve is useful only when its
topology, schedule, revision, dirty state, and non-capacity warning are explicit.

## Decision

- Extend the explicit multi-edge harness with an optional evidence output while
  retaining the existing one-session correctness scenario when no output is
  requested.
- Start two independent gateway JVMs and two independent HAProxy containers.
  Pin primary edge to gateway A and secondary edge to gateway B for deterministic
  fault attribution; this remains a test topology rather than production advice.
- Establish twelve authenticated sessions on primary and six on secondary.
  Force-kill only primary HAProxy while both gateway JVMs remain alive.
- Resume the twelve affected durable sessions through secondary in four batches
  of three, scheduled 100 ms apart. Require exact account/device/session identity,
  rotated resume tokens, zero errors, and secondary authentication reconciliation
  from six to eighteen.
- Record latency, scheduled-start jitter, elapsed time, derived throughput,
  topology, runtime environment, pinned HAProxy image, source revision, and
  worktree state in schema-1 JSON.
- Add an independent strict validator and keep this Docker/local-service gate
  outside ordinary `--all`.

## Consequences

The project can now compare one bounded dual-edge recovery curve across exact
revisions. The scenario exercises TLS termination, secondary-edge admission,
PostgreSQL session truth, two JVMs, Redis-enabled composition, and simultaneous
resume work while six sessions remain connected.

The twelve reconnects are not a supported fleet rate, user-count promise, SLO,
or production capacity. The curve does not measure CPU/RSS, database-pool or
event-loop saturation, multiple hosts, real Web/Windows timing distributions,
DNS/GSLB convergence, or correlated PostgreSQL/Redis failure. Larger curves
require those signals and an isolated performance environment.

## Verification

Run:

```bash
python3 tools/verify_m0.py --gateway-multi-edge \
  --gateway-multi-edge-output /tmp/chat-room-multi-edge-reconnect.json
```

Comparable release evidence must be generated from a clean commit and
revalidated with `tools/multi_edge_reconnect_result.py --require-clean` against
that exact revision.

The first accepted clean record is
[`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_PERFORMANCE_2026-08-14.json`](../../baselines/M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_PERFORMANCE_2026-08-14.json),
measured at revision `ad97e070...`. Its explanatory report preserves the host,
curve, measured percentiles, and unproven production dimensions.

## Rollback

Remove the optional output path, performance scenario, validator, and selector.
The ADR-0381 correctness gate, production runtime, protocols, and schemas remain
unchanged.
