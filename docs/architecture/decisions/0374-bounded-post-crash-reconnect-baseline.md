# ADR-0374: Bounded Post-Crash Reconnect Baseline

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0373 proves one client can recover after an independently running gateway is
force-killed. Existing reconnect performance evidence measures up to ten clients
against one healthy loopback gateway, without HAProxy or a concurrent backend
loss. Neither result measures the added TLS, proxy, scheduling, and survivor-load
cost after a real gateway failure.

A development-host measurement must remain bounded and reproducible. It cannot
be presented as a supported fleet reconnect rate or production capacity.

## Decision

- Extend the explicit gateway-crash harness with an optional evidence output.
  Without output it retains the ADR-0373 correctness scenario; with output it
  selects a separate bounded performance scenario.
- Establish twelve authenticated WSS connections through real HAProxy and
  require least-connections to place exactly six on each independent gateway
  JVM. Use twelve accounts so authentication admission policy is not weakened.
- Force-kill one gateway and keep the six existing survivor sessions active.
  Reconnect the six failed sessions in three batches of two, scheduled 100 ms
  apart, through HAProxy to the survivor. Resume the exact durable session and
  require account, device, session, and token-rotation invariants.
- Record resume latency, scheduled-start jitter, wall time, throughput, attempts,
  successes, errors, environment, HAProxy image digest, source revision, and
  worktree state in a versioned JSON document.
- Add a strict independent validator for identity, bounds, reconciliation,
  monotonic distributions, exact revision, and clean-tree release evidence.
  Keep this Docker/local-service gate outside ordinary `--all`.

## Consequences

This creates comparable local evidence for one specific post-crash arrival
curve. It exercises the production gateway composition, PostgreSQL-backed
session resume, Redis-enabled runtimes, frontend/backend TLS, active HAProxy
health checks, and the surviving gateway's ordinary admission controls.

The six reconnects are deliberately small. Their measured throughput is not a
safe deployment rate, user-count promise, or correlated-failure envelope. A
larger curve requires reviewed authentication limits, longer sampling, CPU/RSS,
database-pool and event-loop saturation signals, and a dedicated environment.

## Verification

Development run:

```bash
python3 tools/verify_m0.py --gateway-crash \
  --gateway-crash-output /tmp/gateway-crash-reconnect.json
```

Release evidence must be generated from a clean commit and revalidated with its
exact revision using `tools/gateway_crash_performance_result.py`.

## Rollback

Remove the optional performance scenario, output flag, and validator. The
ADR-0373 single-client crash correctness gate and all production runtime,
protocol, storage, routing, and HAProxy behavior remain unchanged.
