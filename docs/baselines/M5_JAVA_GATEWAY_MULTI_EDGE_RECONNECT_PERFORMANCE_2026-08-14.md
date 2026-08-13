# M5 Java Gateway Dual-Edge Reconnect Baseline — 2026-08-14

## Scope

This is a clean-revision local failure-recovery curve for ADR-0384. It is not a
production capacity, fleet reconnect rate, SLO, or supported user-count claim.

- source revision: `ad97e070c07c8b8f721a346a41f557a663abd310`;
- worktree at measurement start: clean;
- host: macOS 26.5.2, arm64, 10 available processors;
- Java: 21.0.12, 512 MiB maximum test heap;
- edge: two pinned HAProxy 3.2 containers;
- gateway: two independent Java JVMs with PostgreSQL and Redis composition;
- initial sessions: 12 on primary, 6 on secondary;
- failure: force-kill primary HAProxy only;
- arrival curve: 12 resumes, batches of 3, 100ms between four batches.

## Result

| Measurement | Result |
| --- | ---: |
| Successful resumes | 12 / 12 |
| Errors | 0 |
| Secondary authentication counter | 6 → 18 |
| Resume latency P50 | 21.696 ms |
| Resume latency P95 / P99 / max | 37.212 ms |
| Resume latency mean | 27.106 ms |
| Scheduled-start jitter P50 | 3.651 ms |
| Scheduled-start jitter max | 8.503 ms |
| Controlled elapsed time | 332.116 ms |
| Derived controlled throughput | 36.132 resumes/s |

The primary gateway JVM remained alive, every primary-edge client observed
transport termination, and all six secondary-edge sessions remained connected
before the recovery batches began.

## Reproduction and Validation

```bash
python3 tools/verify_m0.py --gateway-multi-edge \
  --gateway-multi-edge-output /tmp/chat-room-multi-edge-reconnect.json

python3 tools/multi_edge_reconnect_result.py \
  /tmp/chat-room-multi-edge-reconnect.json \
  --expected-revision ad97e070c07c8b8f721a346a41f557a663abd310 \
  --require-clean
```

The committed raw record is
[`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_PERFORMANCE_2026-08-14.json).

## Unproven

The measurement does not include separate physical hosts, production TLS secret
distribution, DNS/GSLB convergence, actual Web/Windows reconnect distributions,
CPU/RSS or database/event-loop saturation, long-duration stability, or a
correlated PostgreSQL/Redis failure. Those remain required before capacity or
availability claims.
