# M5 Java Gateway Multi-Edge Reconnect Process Resource Baseline (2026-08-14)

## Scope

This is the first clean schema version 5 run of the bounded dual-edge reconnect
scenario. It observes authentication workers, PostgreSQL pool pressure, Netty
event loops, process CPU time, and JVM heap in one recovery window. It is local
diagnostic evidence, not a production capacity or SLO claim.

The exact record is
`M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_PROCESS_RESOURCE_SATURATION_2026-08-14.json`.

## Identity

- Source revision: `841b96806df980b56c582e6af2de82cc351d8796`
- Worktree dirty at start: `false`
- Host: macOS 26.5.2, Apple arm64, 10 available processors
- HAProxy: pinned image digest recorded in JSON

The top-level `environment.maximumHeapBytes` describes the Gradle test JVM that
writes evidence. The `processResourceSaturation` heap values come from the
independent secondary gateway process under measurement; they intentionally
describe different JVMs.

## Fixed Scenario

- Two independently killable HAProxy edges and two Java gateways.
- 18 authenticated sessions: 12 affected by primary-edge loss and 6 surviving
  on the secondary edge.
- Affected sessions resume through the secondary edge in four batches of three,
  scheduled 100 ms apart.
- Shared admin snapshot starts target 5 ms; four event-loop probes run at 50 ms.
- The secondary PostgreSQL pool maximum is four connections.

## Result

- Reconnect successes/errors: 12/0.
- Elapsed recovery: 332.132 ms; controlled rate: 36.130 resumes/s.
- Resume latency: 17.459 ms minimum, 20.442 ms P50, 42.722 ms
  P95/P99/maximum, 25.704 ms mean.
- Shared samples: 68.
- Authentication active/queue peaks: 3/0.
- PostgreSQL active/total/waiting peaks: 1/3/1.
- Event-loop probe delta: 28; latest-lag peak: 2.375 ms; pending peak: 0;
  since-start maximum unchanged at 9.073 ms.
- Gateway CPU time: 4,043,623 µs before, 4,357,806 µs after, 314,183 µs
  consumed across a 334 ms uptime interval; CPU time was available for all
  samples.
- Gateway heap used: 304 MiB before, 318 MiB after and at peak.
- Gateway heap committed: 754 MiB before and after; effective maximum: 4 GiB.

CPU time is cumulative across gateway threads. Dividing by elapsed time gives
aggregate core consumption, while normalization across 10 available processors
would answer a different question. This report deliberately does not promote
either calculation to a capacity threshold.

## Verification

```sh
python3 tools/verify_m0.py --gateway-multi-edge \
  --gateway-multi-edge-output \
  docs/baselines/M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_PROCESS_RESOURCE_SATURATION_2026-08-14.json

python3 tools/multi_edge_reconnect_result.py \
  docs/baselines/M5_JAVA_GATEWAY_MULTI_EDGE_RECONNECT_PROCESS_RESOURCE_SATURATION_2026-08-14.json \
  --expected-revision 841b96806df980b56c582e6af2de82cc351d8796 \
  --require-clean
```

## Remaining Limits

The run does not measure RSS, native/off-heap memory, GC pauses, container CPU
quota, host contention, query/connection-acquisition latency, Redis latency,
multi-host effects, production discovery, or real client arrival patterns.
Repeated workload steps are required before locating a saturation knee.
