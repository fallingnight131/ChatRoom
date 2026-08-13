# M5 Java V2 Gateway 10 Active Conversations Baseline — 2026-08-14

## Result

Five authenticated TLS/WSS connections retained 40 live routing subscriptions
across 10 active GROUP conversations with zero errors on commit
`c4caf9ff8c2983029e0c053ff5b888a4ebd29faa`. The machine-readable evidence is
[`M5_JAVA_GATEWAY_ACTIVE_CONVERSATIONS_10_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_ACTIVE_CONVERSATIONS_10_PERFORMANCE_2026-08-14.json)
and passed the schema-7, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 5 | 89.702 ms | 256.908 ms | 256.908 ms | 256.908 ms |
| Per-receiver activation of all 10 conversations | 4 | 7.786 ms | 28.080 ms | 28.080 ms | 28.080 ms |
| Submit to sender `MESSAGE_ACCEPTED` | 100 | 0.665 ms | 1.131 ms | 1.630 ms | 1.652 ms |
| Submit until all 4 peers receive publication | 100 | 0.872 ms | 1.483 ms | 1.959 ms | 2.643 ms |

The workload rotated evenly through all conversations. PostgreSQL reconciled
exactly 11 continuous messages per conversation, 110 total, and the four
receivers observed exactly 400 live publications. The seeded model contained
50 durable memberships and the process-local router retained exactly 40
receiver subscriptions.

## Environment and interpretation

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, `chat.v2`, Hikari, and the
  ADR-0345 bounded process-local router;
- Java peak RSS 460,193,792 bytes and observed heap 287,928,376 bytes;
- PostgreSQL postmaster peak RSS 24,395,776 bytes, not total database memory.

This proves one gateway can preserve exact live and durable behavior for this
bounded 10-conversation/4-receiver workload. It is not a supported-user count
or a reason by itself to add Redis or a broker. The next comparable point uses
the same connection/message shape at the 100-conversation route bound; group
member growth remains a separate fan-out dimension.
