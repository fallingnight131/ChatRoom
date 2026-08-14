# ADR-0394: Repeated Reconnect Ladder Evidence

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0393 defines three fixed reconnect workload profiles. One run per profile
is still too sensitive to development-host scheduling, JIT, garbage collection,
and disposable-service startup effects to support even a local pressure-onset
observation. Repetition and explicit interpretation rules must be part of the
evidence identity rather than applied informally after data collection.

The existing raw evidence exposes peak authentication queue, PostgreSQL waiter,
Netty pending-task/latest-lag, process CPU, heap, and reconnect latency values.
It does not yet expose signal duration, RSS, GC pauses, host utilization, or a
production resource quota.

## Decision

- Run exactly three fresh disposable-environment repetitions of `step-12`,
  then `step-24`, then `step-48`. Embed all nine validated schema-6 child
  records in one self-contained schema-1 ladder record.
- Require every child to have the same source revision, environment identity,
  host identity, and profile placement. Reconcile aggregate dirty state and
  retain the child records instead of discarding raw evidence.
- Mark a run as having a direct pressure signal when at least one of these
  bounded diagnostic rules is true:
  - authentication queue peak is positive;
  - PostgreSQL waiting-thread peak is positive;
  - Netty pending-task peak is positive;
  - latest event-loop probe delay reaches one 50 ms probe period;
  - normalized process CPU reaches 80% of all reported processors during the
    shared window.
- Mark repeated pressure only when at least two of three runs have a direct
  signal. Report the first fixed profile with repeated pressure; if it is the
  lowest profile, state that pressure occurred at or below the observable
  ladder rather than inventing a knee.
- Mark a latency-knee candidate only when median P95 is both at least 2x the
  `step-12` median and at least 10 ms higher. Direct repeated pressure takes
  precedence over this heuristic.
- If neither rule triggers, report only that no pressure knee was observed
  within this ladder. Never report safe capacity, an SLO, or a production
  admission rate.

## Consequences

The result becomes independently reproducible and auditable: changing run
count, order, threshold, profile, child evidence, or conclusion invalidates the
aggregate. Embedding all nine children makes the JSON larger but prevents a
summary from losing its source measurements.

Peak-based signals can overstate transient contention and three local runs have
low statistical power. The outcome is a development-host diagnostic and a
decision aid for the next measurement, not a capacity result. Duration-aware
signals, RSS, GC and isolated-host evidence remain future work.

## Verification

Contract tests cover clean aggregation, fixed run counts/profile placement,
dirty-state reconciliation, direct-pressure majority, latency-candidate rules,
and tamper rejection. The real ladder runs with:

```bash
python3 tools/verify_m0.py \
  --gateway-multi-edge-ladder-output /tmp/chat-room-reconnect-ladder.json
```

Release evidence must be generated from a clean committed revision and
revalidated with `tools/multi_edge_reconnect_ladder_result.py --require-clean`.

## Rollback

Remove the ladder runner, aggregate validator, and aggregate evidence. Keep the
three individually runnable schema-6 profiles from ADR-0393. No production
runtime, protocol, data, deployment, admission, or resource configuration
changes.
