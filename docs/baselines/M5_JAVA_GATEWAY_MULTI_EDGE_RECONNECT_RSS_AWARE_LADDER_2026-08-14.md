# M5 RSS-Aware Multi-Edge Reconnect Ladder — 2026-08-14

## Result

The clean commit-exact aggregate-schema-4 ladder completed all nine real WSS
dual-edge recovery scenarios with zero reconnect errors. The existing
sustained-pressure and latency rules did not identify a knee:

| Profile | Median P50 | Median P95 | Peak-signal runs | Sustained-signal runs |
| --- | ---: | ---: | ---: | ---: |
| `step-12` | 16.722 ms | 38.029 ms | 1 / 3 | 0 / 3 |
| `step-24` | 24.784 ms | 44.873 ms | 3 / 3 | 0 / 3 |
| `step-48` | 39.227 ms | 57.863 ms | 3 / 3 | 1 / 3 |

The strict conclusion is
`no-sustained-pressure-knee-observed-within-ladder`. This is a local diagnostic,
not a supported reconnect rate, safe capacity, or SLO.

## Resident-memory observations

The current macOS host has no approved native RSS provider. All nine runs
therefore recorded zero available samples, zero resident bytes before/after/max,
and zero read-failure delta. Maximum cached sample age ranged from 4.819 to
8.513 seconds because the unsupported snapshot remains unavailable rather than
performing native reads.

This is successful evidence of honest unavailability, not an RSS measurement.
It proves the schema remains valid on an unsupported host and prevents zero
bytes from being interpreted as a process footprint. A positive RSS baseline
still requires the existing Linux `/proc/self/status` provider on a Linux host,
or a separately approved macOS native bridge.

Four of nine runs observed one GC collection in the reconnect window, with
collection-time deltas of 1, 8, 7, and 2 ms. GC collection elapsed time remains
implementation-dependent and is not exact stop-the-world pause time.

## Evidence identity

- Source revision: `5c50a0b200d882cb211a46ef44dedb3a12204d5b`
- Worktree dirty: `false`
- Recorded at: `2026-08-14T01:28:54.088098Z`
- Host: `macOS-26.5.2-arm64-arm-64bit`, Apple Silicon/aarch64
- Java: `21.0.12`; test-JVM maximum heap: 512 MiB; processors: 10
- Python: `3.9.6`
- HAProxy: pinned image digest recorded in the JSON
- Per run: two gateway JVMs, two HAProxy edges, disposable PostgreSQL and
  Redis, six survivor sessions, and four 100 ms-spaced reconnect batches

The self-contained evidence is
[`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_RSS_AWARE_LADDER_2026-08-14.json`](M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_RSS_AWARE_LADDER_2026-08-14.json).
It embeds all nine raw-schema-9 child records.

## Verification

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/tmp/chat-room-gradle-home \
python3 tools/verify_haproxy_multi_edge_ladder.py \
  --output /tmp/chat-room-rss-aware-ladder-clean.json

python3 tools/multi_edge_reconnect_ladder_result.py \
  /tmp/chat-room-rss-aware-ladder-clean.json \
  --expected-revision 5c50a0b200d882cb211a46ef44dedb3a12204d5b \
  --require-clean
```

## Limits and next measurement

The evidence is loopback, single-development-host, and only three repetitions.
It does not represent Web/Windows fleet timing, multiple hosts, internet or
discovery latency, positive RSS, exact GC pauses, direct/native allocation,
container quotas, host contention, or production traffic mixtures.

The next RSS gate is a clean Linux-host run that proves positive cached
`VmRSS`, bounded sample age, and no provider failures. macOS native support
requires a separate dependency, packaging, security, and host-test decision;
Windows Java server support remains outside the current deployment promise.
