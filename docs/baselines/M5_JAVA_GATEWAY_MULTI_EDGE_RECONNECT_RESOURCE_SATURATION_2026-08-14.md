# M5 Java Gateway Multi-Edge Reconnect Resource Saturation Baseline (2026-08-14)

## Scope

This is the first clean schema version 3 run of the bounded dual-edge reconnect
scenario. It combines ADR-0384 recovery behavior, ADR-0386 authentication
saturation sampling, and ADR-0388 PostgreSQL pool sampling in the same loopback
metrics snapshots. It is local diagnostic evidence, not a production capacity
or SLO claim.

The exact machine-readable record is
`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_RESOURCE_SATURATION_2026-08-14.json`.

## Identity

- Source revision: `b46768e5894d381cb2fc9138e639223bee58579b`
- Worktree dirty at start: `false`
- Java: 21.0.12, maximum heap 512 MiB
- Host: macOS 26.5.2, Apple arm64, 10 available processors
- HAProxy: pinned image digest recorded in JSON

## Fixed Scenario

- Two independently killable HAProxy edges and two Java gateways.
- 18 authenticated sessions: 12 on the failed primary edge and 6 surviving on
  the secondary edge.
- Only the primary edge is force-killed.
- The 12 affected sessions resume through the secondary edge in four batches of
  three, scheduled 100 ms apart over a 300 ms span.
- Shared loopback snapshot starts target a 5 ms cadence from immediately before
  reconnect release until every reconnect future finishes. Overrun slots are
  not followed by another fixed delay.
- The secondary gateway PostgreSQL pool maximum is fixed at four connections.

## Result

- Reconnect successes/errors: 12/0.
- Secondary authentication accepted count: 6 before, 18 after.
- Elapsed recovery window: 332.365 ms.
- Controlled reconnect throughput: 36.105 resumes/s.
- Resume latency: 16.235 ms minimum, 23.276 ms P50, 29.216 ms
  P95/P99/maximum, 23.629 ms mean.
- Scheduled-start jitter: 0.021 ms minimum, 5.123 ms P50, 9.331 ms
  P95/P99/maximum, 5.013 ms mean.
- Shared saturation samples: 68.
- Authentication active-worker/queue peaks: 3/0.
- PostgreSQL pool metrics unavailable samples: 0.
- PostgreSQL active/total/waiting-thread peaks: 1/3/1, with configured maximum 4.

The run observed a transient PostgreSQL pool waiter even though the
authentication executor queue stayed at zero. This explains a resource boundary
that worker-only sampling cannot see; it does not prove the connection pool was
the dominant contributor to each latency sample.

## Verification

The run was generated with:

```sh
python3 tools/verify_m0.py --gateway-multi-edge \
  --gateway-multi-edge-output \
  docs/baselines/M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_RESOURCE_SATURATION_2026-08-14.json
```

The committed JSON passed:

```sh
python3 tools/multi_edge_reconnect_result.py \
  docs/baselines/M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_RESOURCE_SATURATION_2026-08-14.json \
  --expected-revision b46768e5894d381cb2fc9138e639223bee58579b \
  --require-clean
```

## Remaining Limits

Hikari gauges are instantaneous and independently read. The benchmark does not
measure connection-acquisition duration, query latency, PostgreSQL server
saturation, CPU, memory, Netty event-loop lag, Redis latency, multi-host effects,
discovery behavior, or real Web/Windows reconnect distributions. A controlled
workload ladder and those signals are still required to locate a saturation
knee.
