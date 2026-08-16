# M5 Direct-Buffer-Aware Multi-Edge Reconnect Ladder — 2026-08-16

## Result

The clean commit-exact aggregate-schema-5 ladder completed all nine real WSS
dual-edge recovery scenarios with zero reconnect errors. The existing
sustained-pressure and latency rules did not identify a knee:

| Profile | Median P50 | Median P95 | Peak-signal runs | Sustained-signal runs |
| --- | ---: | ---: | ---: | ---: |
| `step-12` | 19.913 ms | 34.399 ms | 3 / 3 | 0 / 3 |
| `step-24` | 23.059 ms | 42.559 ms | 3 / 3 | 0 / 3 |
| `step-48` | 38.264 ms | 57.603 ms | 3 / 3 | 0 / 3 |

The strict conclusion is
`no-sustained-pressure-knee-observed-within-ladder`. Median `step-48` P95 was
about 1.67 times the `step-12` value, below the existing 2x latency-candidate
rule. This remains a local diagnostic, not a supported reconnect rate, safe
capacity, or SLO.

## Direct-buffer observations

Every direct-buffer observation in all nine runs was available. Per-run maximum
estimated used memory ranged from 9,042,450 to 10,615,326 bytes (about 8.62 to
10.12 MiB), and maximum buffer count ranged from 66 to 70. Every maximum covered
its before/after endpoints.

The values generally increased while reconnecting, but the small fixed ladder
does not establish allocation rate, retention, a leak, or a safe memory limit.
The standard MXBean estimate covers direct buffers only; it is not complete
off-heap/native memory or RSS.

Two runs observed GC counter movement: `step-24` run 1 recorded one collection
and 1 ms collection-time delta; `step-48` run 3 recorded three collections and
2 ms. Collection elapsed time is not exact stop-the-world pause duration.

RSS remained unavailable on the macOS evidence host in every sample, so no
resident-memory conclusion is drawn from this baseline.

## Evidence identity

- Source revision: `0c50614e45fd6a49d102997da8ce26c9310e21b4`
- Worktree dirty: `false`
- Recorded at: `2026-08-16T04:16:47.663237Z`
- Host: `macOS-26.5.2-arm64-arm-64bit`, Apple Silicon/aarch64
- Java: `21.0.12`; test-JVM maximum heap: 512 MiB; processors: 10
- Python: `3.9.6`
- HAProxy: pinned image digest recorded in the JSON
- Per run: two gateway JVMs, two HAProxy edges, disposable PostgreSQL and
  Redis, six survivor sessions, and four 100 ms-spaced reconnect batches

The self-contained evidence is
[`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_DIRECT_BUFFER_AWARE_LADDER_2026-08-16.json`](M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_DIRECT_BUFFER_AWARE_LADDER_2026-08-16.json).
It embeds all nine raw-schema-10 child records.

## Verification

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/tmp/chat-room-gradle-home-20260816 \
python3 tools/verify_haproxy_multi_edge_ladder.py \
  --output /tmp/chat-room-direct-buffer-ladder-clean.json

python3 tools/multi_edge_reconnect_ladder_result.py \
  /tmp/chat-room-direct-buffer-ladder-clean.json \
  --expected-revision 0c50614e45fd6a49d102997da8ce26c9310e21b4 \
  --require-clean
```

## Limits and next measurement

The evidence is loopback, single-development-host, and only three repetitions.
It does not represent Web/Windows fleet timing, multiple hosts, internet or
discovery latency, allocation rate, exact GC pauses, positive RSS, complete
native memory, container quotas, host contention, or production traffic mixes.

A longer steady-state soak with a fixed message/reconnect arrival model is
needed before evaluating whether direct-buffer usage returns to a stable band.
The separate full Linux schema-10 ladder is still required for simultaneous
positive RSS and direct-buffer observations.
