# M5 GC-Aware Multi-Edge Reconnect Ladder — 2026-08-14

## Result

The clean commit-exact schema-3 ladder completed all nine real WSS dual-edge
recovery scenarios with zero reconnect errors. The sustained-pressure and
latency rules did not identify a knee:

| Profile | Median P50 | Median P95 | Peak-signal runs | Sustained-signal runs |
| --- | ---: | ---: | ---: | ---: |
| `step-12` | 23.211 ms | 47.317 ms | 2 / 3 | 0 / 3 |
| `step-24` | 29.208 ms | 43.522 ms | 3 / 3 | 0 / 3 |
| `step-48` | 50.338 ms | 68.537 ms | 2 / 3 | 0 / 3 |

The strict conclusion is
`no-sustained-pressure-knee-observed-within-ladder`. This remains a local
diagnostic, not a supported reconnect rate, safe capacity, or SLO.

## GC collection observations

- Seven of nine runs observed zero collection-count and collection-time delta.
- `step-12` run 3 observed one collection and 1 ms collection-time delta.
- `step-24` run 1 observed one collection and 3 ms collection-time delta.
- No `step-48` run observed a GC counter increase.

The two small positive deltas show that collection activity can overlap the
window, but they do not track the profile ordering or explain the overall
latency curve. Collection time is JMX implementation-dependent elapsed time,
not exact stop-the-world pause duration.

Every authentication-queue, PostgreSQL-waiter, and Netty-pending longest streak
was zero or one sample. Normalized process CPU ranged from 0.080683 to 0.193645
of all ten reported processors. Median `step-48` P95 was only 1.448x the
`step-12` value, below the 2x latency-candidate rule.

## Evidence identity

- Source revision: `7e9e7ba0a30d043650e81ce32f3bcc73b27d0d28`
- Worktree dirty: `false`
- Recorded at: `2026-08-14T00:39:29.921009Z`
- Host: `macOS-26.5.2-arm64-arm-64bit`, Apple Silicon/aarch64
- Java: `21.0.12`; test-JVM maximum heap: 512 MiB; processors: 10
- Python: `3.9.6`
- HAProxy: pinned image digest recorded in the JSON
- Per run: two gateway JVMs, two HAProxy edges, disposable PostgreSQL and
  Redis, six survivor sessions, and four 100 ms-spaced reconnect batches

The self-contained evidence is
[`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_GC_AWARE_LADDER_2026-08-14.json`](M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_GC_AWARE_LADDER_2026-08-14.json).
It embeds all nine schema-8 child records.

## Verification

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/tmp/chat-room-gradle-home \
python3 tools/verify_m0.py \
  --gateway-multi-edge-ladder-output \
  /tmp/chat-room-reconnect-gc-ladder-clean.json

python3 tools/multi_edge_reconnect_ladder_result.py \
  /tmp/chat-room-reconnect-gc-ladder-clean.json \
  --expected-revision 7e9e7ba0a30d043650e81ce32f3bcc73b27d0d28 \
  --require-clean
```

## Limits and next measurement

The evidence is loopback, single-development-host, and only three repetitions.
It does not represent Web/Windows fleet timing, multiple hosts, internet or
discovery latency, exact GC pauses, allocation rate, RSS, direct/native memory,
container quotas, host contention, or production traffic mixtures.

Portable RSS is not available through the selected standard JDK APIs. The next
step should define an explicit platform adapter with an availability flag and
unit semantics, implement the macOS development-host probe without changing
the Windows product support promise, and require a separate Windows provider
before treating RSS as a cross-platform release signal.
