# M5 Java V2 Gateway 40-receiver GROUP Baseline — 2026-08-14

## Result

One production TLS/WSS gateway delivered every measured message to 40 caught-up
GROUP receivers with zero errors on commit
`f6188674b77a7ed4b465eb0e50ad09f8ef278b7c`. The machine-readable evidence is
[`M5_JAVA_GATEWAY_GROUP_40_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_GROUP_40_PERFORMANCE_2026-08-14.json)
and passed the schema-2, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 41 | 79.087 ms | 94.520 ms | 262.465 ms | 262.465 ms |
| Submit to sender `MESSAGE_ACCEPTED` | 100 | 0.585 ms | 1.179 ms | 1.828 ms | 2.292 ms |
| Submit until all 40 peers receive publication | 100 | 1.266 ms | 2.405 ms | 4.259 ms | 4.653 ms |

PostgreSQL reconciled exactly 110 continuous messages and the receivers observed
exactly 4,000 live publications. The earlier 16-receiver baseline measured
2.068 ms all-peer P95; this 40-receiver run measured 2.405 ms, about 16% higher
on the same host class. The runs use different commits, so this is directional
rather than a controlled code-regression comparison.

## Environment and interpretation

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, `chat.v2`, Hikari, and process-local
  live router;
- Java peak RSS 563,249,152 bytes and observed heap 191,546,064 bytes;
- PostgreSQL postmaster peak RSS 24,395,776 bytes, not total database memory.

The measured 40-receiver fan-out does not show a present single-gateway
bottleneck and does not justify a broker. It also does not model hundreds or
thousands of group members, remote networks, concurrent senders, or multiple
gateways. The default direct-peer authentication window limits this bounded
loopback harness to 59 receivers; higher fan-out needs a distributed load
driver that does not weaken production admission controls.
