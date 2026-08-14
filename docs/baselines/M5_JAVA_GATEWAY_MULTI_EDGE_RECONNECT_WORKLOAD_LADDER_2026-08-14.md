# M5 Java Gateway Multi-Edge Reconnect Workload Ladder — 2026-08-14

## Result

The clean commit-exact three-by-three local ladder completed all nine real WSS
dual-edge recovery scenarios with zero reconnect errors. The strict aggregate
classified the first repeated pressure observation at `step-24`:

| Profile | Affected / batch | Median P50 | Median P95 | Runs with pressure signal |
| --- | ---: | ---: | ---: | ---: |
| `step-12` | 12 / 3 | 19.978 ms | 42.011 ms | 1 / 3 |
| `step-24` | 24 / 6 | 32.892 ms | 47.650 ms | 2 / 3 |
| `step-48` | 48 / 12 | 45.481 ms | 68.626 ms | 3 / 3 |

The aggregate conclusion is
`repeated-pressure-first-observed-at-step-24`. This means only that, on this
host and fixed topology, the majority rule first changed between `step-12` and
`step-24`. It is not a supported reconnect rate, saturation limit, SLO, or
production capacity result.

The latency rule did not identify a knee candidate: `step-48` median P95 was
1.633x `step-12`, below the required 2x ratio even though the absolute increase
was greater than 10 ms.

## Pressure observations

- `step-12`: two runs had no direct signal; one observed a PostgreSQL waiter
  peak of 2.
- `step-24`: one run observed a PostgreSQL waiter peak of 1 plus one Netty
  pending task, one had no direct signal, and one observed a PostgreSQL waiter
  peak of 2.
- `step-48`: one run observed one Netty pending task, one observed a PostgreSQL
  waiter peak of 2 plus two pending tasks, and one observed a waiter peak of 1
  plus one pending task.
- Authentication queue peak was zero in all nine runs. Latest event-loop probe
  lag stayed between 2.093 and 3.131 ms, below the 50 ms rule.
- Normalized gateway process CPU ranged from 0.078873 to 0.170836 of all ten
  reported processors, below the 0.8 rule.

These are instantaneous sampled peaks. The raw evidence does not yet establish
how long PostgreSQL waiters or pending tasks persisted, and therefore cannot
distinguish a single short scheduling overlap from sustained resource pressure.

## Evidence identity

- Source revision: `af020e6c28941fff502cbda60e12920506566edb`
- Worktree dirty: `false`
- Recorded at: `2026-08-14T00:17:53.835281Z`
- Host: `macOS-26.5.2-arm64-arm-64bit`, Apple Silicon/aarch64
- Java: `21.0.12`; test-JVM maximum heap: 512 MiB; processors: 10
- Python: `3.9.6`
- HAProxy: pinned `haproxy:3.2-alpine` digest recorded in the JSON
- Topology per run: two Java gateway JVMs, two independent HAProxy edge
  processes, disposable PostgreSQL and Redis, six surviving sessions, four
  reconnect batches scheduled 100 ms apart

The self-contained strict record is
[`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_WORKLOAD_LADDER_2026-08-14.json`](M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_WORKLOAD_LADDER_2026-08-14.json).
It embeds all nine schema-6 child records rather than only this summary.

## Verification

The ladder was generated with:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
GRADLE_USER_HOME=/tmp/chat-room-gradle-home \
python3 tools/verify_m0.py \
  --gateway-multi-edge-ladder-output /tmp/chat-room-reconnect-ladder-clean.json
```

It was then independently revalidated with:

```bash
python3 tools/multi_edge_reconnect_ladder_result.py \
  /tmp/chat-room-reconnect-ladder-clean.json \
  --expected-revision af020e6c28941fff502cbda60e12920506566edb \
  --require-clean
```

## Limits and next measurement

This loopback development-host ladder does not represent Web/Windows fleet
arrival distributions, internet latency, DNS convergence, multiple hosts,
container CPU quotas, RSS, native/off-heap memory, or GC pauses. It embeds
measurement overhead and uses only three repetitions.

The next useful refinement is duration-aware sampling: count positive queue,
waiter, and pending-task samples and longest consecutive streaks in the same
window. That can separate a transient peak from sustained pressure before any
larger workload step or production sizing claim is considered.
