# M5 Java V2 Gateway GROUP Fan-out Baseline — 2026-08-14

## Result

The 16-receiver TLS/WSS GROUP scenario completed with zero errors on commit
`ff7ed91ac2d3552309920d3ca16572d7e31b235a`. The machine-readable evidence is
[`M5_JAVA_GATEWAY_GROUP_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_GROUP_PERFORMANCE_2026-08-14.json)
and passed the schema-2, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 17 | 81.148 ms | 269.027 ms | 269.027 ms | 269.027 ms |
| Submit to sender `MESSAGE_ACCEPTED` | 100 | 0.740 ms | 1.488 ms | 1.817 ms | 1.934 ms |
| Submit until all 16 peers receive `MESSAGE_PUBLISHED` | 100 | 1.181 ms | 2.068 ms | 2.330 ms | 2.517 ms |

Measured completed-message throughput was 806.175 messages/second. Every one of
the 100 measured messages was committed, acknowledged, and delivered to all 16
caught-up peers, producing exactly 1,600 verified peer publications. The
database contained exactly 110 durable messages after 10 warm-ups.

## Environment and scenario

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, `chat.v2` Protobuf, and Hikari;
- one GROUP sender and 16 authenticated, caught-up Windows-endpoint receivers;
- sequential 256-byte text messages and all-peer completion timing;
- Java peak RSS 573,325,312 bytes and observed heap 158,795,448 bytes;
- PostgreSQL postmaster peak RSS 24,428,544 bytes. This is not total database
  process memory.

## Interpretation

This result establishes a reproducible small active-group comparison point. It
does not represent a large group, concurrent senders, a sustained soak, a
remote network, slow consumers, or production capacity. The connection setup
distribution includes one cold sender followed by warm peer setup and should
not be interpreted as an independent authentication benchmark.

The lower completed-message throughput and higher all-peer latency relative to
the one-peer baseline are directionally consistent with additional serialized
local fan-out work, but the runs use different source commits and are not a
controlled scaling curve. A later fan-out study should run multiple receiver
counts from one clean commit and report the complete curve.

This evidence still does not justify Redis, a broker, or multiple gateways. The
next M5 slice should measure reconnect/session-resume storms separately from
message fan-out so authentication admission, worker queues, and recovery rate
are visible without conflating them with durable message throughput.
