# M5 Java Gateway Multi-Edge Reconnect Event-Loop Saturation Baseline (2026-08-14)

## Scope

This is the first clean schema version 4 run of the bounded dual-edge reconnect
scenario. It samples authentication workers, the PostgreSQL pool, and Netty
event-loop probes in one recovery window. It is local diagnostic evidence, not
a production capacity or SLO claim.

The exact machine-readable record is
`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_EVENT_LOOP_SATURATION_2026-08-14.json`.

## Identity

- Source revision: `14e03b674a34cd45dfd9c9b3acaec3baa39e493a`
- Worktree dirty at start: `false`
- Java: 21.0.12, maximum heap 512 MiB
- Host: macOS 26.5.2, Apple arm64, 10 available processors
- HAProxy: pinned image digest recorded in JSON

## Fixed Scenario

- Two independently killable HAProxy edges and two Java gateways.
- 18 authenticated sessions: 12 on the failed primary edge and 6 surviving on
  the secondary edge.
- The primary edge alone is killed; affected sessions resume through the
  secondary edge in four batches of three, scheduled 100 ms apart.
- Shared admin snapshot starts target a 5 ms cadence across the reconnect
  window. Four product event-loop probes run independently at 50 ms fixed rate.
- The secondary gateway PostgreSQL pool maximum is four connections.

## Result

- Reconnect successes/errors: 12/0.
- Secondary authentication accepted count: 6 before, 18 after.
- Elapsed recovery window: 331.523 ms.
- Controlled reconnect throughput: 36.197 resumes/s.
- Resume latency: 15.822 ms minimum, 17.875 ms P50, 53.992 ms
  P95/P99/maximum, 31.259 ms mean.
- Scheduled-start jitter: 0.017 ms minimum, 6.215 ms P50, 8.125 ms
  P95/P99/maximum, 4.899 ms mean.
- Shared saturation samples: 68.
- Authentication active-worker/queue peaks: 1/0.
- PostgreSQL active/total/waiting-thread peaks: 1/2/0, with configured maximum 4.
- Event-loop metrics unavailable samples: 0; workers: 4.
- Event-loop probe samples: 392 before, 420 after, delta 28.
- Maximum latest event-loop lag observed in the window: 2.759 ms.
- Since-start maximum event-loop lag: 24.897 ms before and after the window.
- Maximum pending event-loop tasks observed: 0.

The unchanged since-start maximum and low latest-lag observations provide no
evidence that an event-loop stall drove this run's 53.992 ms tail. They do not
prove that a shorter-than-50-ms stall did not occur. The latency difference from
earlier local runs also demonstrates that one short same-host curve is too noisy
for an SLO or regression threshold.

## Verification

The run was generated with:

```sh
python3 tools/verify_m0.py --gateway-multi-edge \
  --gateway-multi-edge-output \
  docs/baselines/M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_EVENT_LOOP_SATURATION_2026-08-14.json
```

The committed JSON passed:

```sh
python3 tools/multi_edge_reconnect_result.py \
  docs/baselines/M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_EVENT_LOOP_SATURATION_2026-08-14.json \
  --expected-revision 14e03b674a34cd45dfd9c9b3acaec3baa39e493a \
  --require-clean
```

## Remaining Limits

The benchmark does not measure CPU, heap evolution, RSS, GC pauses, query or
connection-acquisition latency, Redis latency, multi-host effects, production
discovery, or real Web/Windows reconnect distributions. Repeated runs and a
controlled workload ladder are required before locating a saturation knee.
