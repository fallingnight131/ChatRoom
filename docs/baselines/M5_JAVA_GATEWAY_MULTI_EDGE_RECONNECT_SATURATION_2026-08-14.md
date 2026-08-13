# M5 Java Gateway Multi-Edge Reconnect Saturation Baseline (2026-08-14)

## Scope

This is the first clean schema version 2 run of the bounded dual-edge reconnect
scenario defined by ADR-0384 and extended by ADR-0386. It is local failure-
recovery and saturation-diagnostic evidence, not a production capacity or SLO
claim.

The exact machine-readable record is
`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_SATURATION_2026-08-14.json`.

## Identity

- Source revision: `4d9574f8c936dfc733a40982994a2eac8e0bd721`
- Worktree dirty at start: `false`
- Java: 21.0.12, maximum heap 512 MiB
- Host: macOS 26.5.2, Apple arm64, 10 available processors
- HAProxy: pinned `haproxy:3.2-alpine` digest recorded in JSON

## Fixed Scenario

- Two separately killable HAProxy edge containers and two Java gateways.
- 18 authenticated sessions: 12 on the failed primary edge and 6 surviving on
  the secondary edge.
- The primary edge alone is force-killed.
- 12 sessions resume through the secondary edge in four batches of three,
  scheduled 100 ms apart over a 300 ms span.
- The secondary gateway loopback metrics are sampled every 5 ms from immediately
  before reconnect release until all reconnect futures finish.

## Result

- Reconnect successes/errors: 12/0.
- Secondary authentication accepted count: 6 before, 18 after.
- Elapsed recovery window: 340.132 ms.
- Controlled reconnect throughput: 35.280 resumes/s.
- Resume latency: 17.175 ms minimum, 21.804 ms P50, 32.788 ms P95/P99/maximum,
  23.417 ms mean.
- Scheduled-start jitter: 0.040 ms minimum, 3.258 ms P50, 10.019 ms
  P95/P99/maximum, 5.469 ms mean.
- Authentication saturation samples: 38.
- Maximum observed active authentication workers: 1.
- Maximum observed queued authentication work: 0.

The run observed real authentication activity and no queued work at the fixed
sampling interval. It does not prove a larger arrival burst would avoid queueing,
nor does it bound unobserved peaks between samples.

## Verification

The run was generated with:

```sh
python3 tools/verify_m0.py --gateway-multi-edge \
  --gateway-multi-edge-output \
  docs/baselines/M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_SATURATION_2026-08-14.json
```

The committed JSON passed:

```sh
python3 tools/multi_edge_reconnect_result.py \
  docs/baselines/M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_SATURATION_2026-08-14.json \
  --expected-revision 4d9574f8c936dfc733a40982994a2eac8e0bd721 \
  --require-clean
```

## Remaining Limits

The benchmark does not measure PostgreSQL connection-pool utilization or wait,
Netty event-loop lag, Redis latency, CPU, memory, multi-host network effects,
discovery behavior, or real Web/Windows reconnect distributions. Those signals
and higher fixed workload steps are required before locating a saturation knee.
