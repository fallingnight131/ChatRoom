# ADR-0393: Fixed Dual-Edge Reconnect Workload Ladder

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0384 and ADR-0386 through ADR-0392 progressively add resource observations
to one fixed 12-session reconnect curve. That curve is reproducible, but a
single point cannot show whether pressure changes as reconnect concentration
increases. Free-form connection and batch arguments would make results easy to
produce but difficult to compare or audit.

The development host also has a default 60-connection peer admission window.
Any local ladder must stay inside that boundary and must not be interpreted as
fleet capacity, an SLO, or a production admission setting.

## Decision

- Upgrade new evidence to schema version 6 and preserve schemas 1 through 5 as
  historical contracts.
- Add a required `workloadProfile` to schema 6. Only these fixed profiles are
  valid:
  - `step-12`: 12 affected, 6 surviving, batches of 3;
  - `step-24`: 24 affected, 6 surviving, batches of 6;
  - `step-48`: 48 affected, 6 surviving, batches of 12.
- Keep every profile at four batches, 100 ms between scheduled batch starts,
  and a 300 ms scheduled span. This changes the reconnect concentration per
  batch while retaining the fault topology and schedule shape.
- Keep `step-12` as the default so existing invocations remain bounded. Select
  another profile only by its reviewed name; do not expose arbitrary counts.
- Require the strict validator to reconcile the profile name with all
  connection and schedule fields. Preserve the existing topology, zero-error,
  identity, resource, revision, and dirty-worktree checks.
- Treat individual profile runs as raw observations. Pressure-onset analysis
  requires repeated clean runs and a separate versioned aggregation contract.

## Consequences

The project can now collect comparable observations at three increasing local
arrival concentrations without silently changing the benchmark identity. The
largest profile uses 54 established sessions and remains below the existing
60-connection development-host admission boundary.

The steps do not model a real Web/Windows fleet distribution, multi-host
contention, internet latency, DNS convergence, RSS, GC pauses, or production
resource quotas. A successful `step-48` run is not evidence that 48 reconnects
per batch are safe in production. Conversely, a noisy local run is not a
capacity limit.

## Verification

Contract tests must accept exactly the three schema-6 profiles, reject unknown
or internally inconsistent profiles, preserve schemas 1 through 5, and retain
all resource reconciliation checks. Each real profile is run with:

```bash
python3 tools/verify_m0.py --gateway-multi-edge \
  --gateway-multi-edge-output /tmp/chat-room-step-24.json \
  --gateway-multi-edge-workload step-24
```

## Rollback

Return new evidence to schema version 5, remove the profile selector, and use
the fixed 12/6/3/100 scenario. Historical evidence and production runtime,
protocol, data, admission, and deployment behavior remain unchanged.
