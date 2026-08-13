# M5 HAProxy Gateway Crash/Reconnect Baseline — 2026-08-14

## Result

Twelve TLS/WSS clients were placed evenly across two independent Java gateway
JVMs by the real digest-pinned HAProxy edge. One JVM was force-killed without a
shutdown hook. Its six clients resumed their exact PostgreSQL-backed sessions on
the surviving gateway in three batches of two scheduled 100 ms apart, with zero
errors on clean commit `1456ca250916f286c5309f808f6a6aedc380b3c9`.

The machine-readable evidence is
[`M5_JAVA_GATEWAY_HAPROXY_CRASH_RECONNECT_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_HAPROXY_CRASH_RECONNECT_PERFORMANCE_2026-08-14.json).
It passed schema 1, exact-revision, clean-worktree, distribution, and
attempt/success/error reconciliation gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| HAProxy TLS/WSS plus session resume | 6 | 29.745 ms | 44.200 ms | 44.200 ms | 44.200 ms |
| Scheduled-start jitter | 6 | 3.427 ms | 10.043 ms | 10.043 ms | 10.043 ms |

The three batches occupied a 200 ms scheduled span and completed in 241.435 ms.
Measured end-to-end resume throughput, including deliberate pacing, was 24.851
successful resumes per second. Every resume retained the exact account, device,
and session identity and rotated its resume token. The six unaffected sessions
remained connected to the surviving gateway while the failed side recovered.

## Interpretation

The earlier healthy single-gateway 100 ms curve measured 12.798 ms resume P95
and 23.904 resumes per second for 50 operations. This post-crash curve measured
44.200 ms P95 and 24.851 resumes per second for six operations through HAProxy.
The results are not directly comparable: this run includes frontend TLS,
backend TLS, proxy routing, an abrupt backend loss, and doubled connection load
on the survivor, while using far fewer samples. The difference is a useful
local warning that healthy single-node resume latency must not be used as the
failure-recovery envelope.

The Web and Windows clients' full-jitter exponential reconnect policy should
remain unchanged. A 500 ms initial ceiling spreads a real fleet more
conservatively than this intentionally compact 100 ms test curve. This result
does not justify a supported-user count, safe fleet reconnect rate, or reduced
backoff.

## Environment and limits

- macOS 26.5.2 on arm64 with 10 logical processors;
- OpenJDK 21.0.12 with a 512 MiB maximum test heap;
- PostgreSQL 17.10 and Redis on numeric host loopback;
- two independently running production `GatewayMain` JVMs;
- official HAProxy 3.2 Alpine image pinned by manifest digest;
- real frontend/backend TLS, WSS, session persistence, authentication admission,
  active backend health checks, and least-connections placement.

This bounded same-host run does not measure CPU/RSS, database-pool saturation,
event-loop queue depth, correlated host loss, mixed-version rollout, or a long
soak. Those measurements are required before increasing the curve or making a
production capacity decision.

## Reproduction

```bash
python3 tools/verify_m0.py --gateway-crash \
  --gateway-crash-output docs/baselines/M5_JAVA_GATEWAY_HAPROXY_CRASH_RECONNECT_PERFORMANCE_2026-08-14.json

python3 tools/gateway_crash_performance_result.py \
  docs/baselines/M5_JAVA_GATEWAY_HAPROXY_CRASH_RECONNECT_PERFORMANCE_2026-08-14.json \
  --expected-revision 1456ca250916f286c5309f808f6a6aedc380b3c9 \
  --require-clean
```
