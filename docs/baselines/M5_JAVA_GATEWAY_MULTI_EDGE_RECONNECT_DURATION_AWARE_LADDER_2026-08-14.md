# M5 Duration-Aware Multi-Edge Reconnect Ladder — 2026-08-14

## Result

The clean commit-exact schema-2 ladder completed all nine real WSS dual-edge
recovery scenarios with zero reconnect errors. Adding sampled duration changed
the interpretation of peak pressure:

| Profile | Median P50 | Median P95 | Runs with any peak | Runs with sustained signal |
| --- | ---: | ---: | ---: | ---: |
| `step-12` | 22.176 ms | 42.375 ms | 2 / 3 | 0 / 3 |
| `step-24` | 28.525 ms | 43.137 ms | 2 / 3 | 1 / 3 |
| `step-48` | 46.476 ms | 78.394 ms | 3 / 3 | 0 / 3 |

The strict conclusion is
`no-sustained-pressure-knee-observed-within-ladder`. No profile met the
two-of-three sustained-pressure rule, and no profile met the latency candidate
rule. This is not evidence that `step-48` is safe production capacity; it says
only that this local three-run ladder did not observe the defined sustained
candidate.

## Duration observations

- `step-12`: two runs observed one PostgreSQL-waiter sample each; both longest
  streaks were one. The third run observed none.
- `step-24`: one run observed one PostgreSQL-waiter sample and two consecutive
  Netty pending-task samples, so only that run counted as sustained. A second
  run observed one authentication-queue sample; the third observed none.
- `step-48`: one run observed five Netty pending-task samples, but none were
  consecutive; the other runs observed isolated authentication-queue,
  PostgreSQL-waiter, or pending-task samples. Every longest streak was one.
- Latest event-loop lag stayed between 2.191 and 2.770 ms, below the 50 ms rule.
- Normalized gateway CPU stayed between 0.091952 and 0.184847 of all ten
  reported processors, below the 0.8 rule.

The previous peak-only clean ladder first classified repeated pressure at
`step-24`. This duration-aware result demonstrates that most of those sampled
peaks were isolated. It does not prove they were harmless: target cadence can
drift and transitions shorter than one metrics read may be missed.

## Evidence identity

- Source revision: `f5400819ec70707442622b6130d76f7bad47940f`
- Worktree dirty: `false`
- Recorded at: `2026-08-14T00:28:46.049344Z`
- Host: `macOS-26.5.2-arm64-arm-64bit`, Apple Silicon/aarch64
- Java: `21.0.12`; test-JVM maximum heap: 512 MiB; processors: 10
- Python: `3.9.6`
- HAProxy: pinned image digest recorded in the JSON
- Per run: two gateway JVMs, two independent HAProxy edges, disposable
  PostgreSQL and Redis, six survivor sessions, and four 100 ms-spaced batches

The self-contained strict evidence is
[`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_DURATION_AWARE_LADDER_2026-08-14.json`](M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_DURATION_AWARE_LADDER_2026-08-14.json).
It embeds all nine schema-7 child records.

## Verification

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/tmp/chat-room-gradle-home \
python3 tools/verify_m0.py \
  --gateway-multi-edge-ladder-output \
  /tmp/chat-room-reconnect-duration-ladder-clean.json

python3 tools/multi_edge_reconnect_ladder_result.py \
  /tmp/chat-room-reconnect-duration-ladder-clean.json \
  --expected-revision f5400819ec70707442622b6130d76f7bad47940f \
  --require-clean
```

## Limits and next measurement

The test remains loopback, development-host, three-repetition evidence. It does
not model Web/Windows fleet timing, multiple hosts, internet or discovery
latency, container quotas, RSS, GC pauses, native memory, host contention, or
production traffic mixtures.

Before increasing the fixed ladder, the next useful resource gap is GC and RSS
evidence for the independent gateway child JVM. Heap-used peaks alone cannot
explain native/direct-buffer pressure or stop-the-world pauses, and the current
portable metrics intentionally omit both.
