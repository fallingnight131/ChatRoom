# M5 Java V2 Single-Gateway Messaging Baseline — 2026-08-14

## Result

The default two-connection TLS/WSS scenario completed with zero errors on
commit `6245067f57b8f4f006148da44653ab057b4c13e8`. The machine-readable evidence is
[`M5_JAVA_GATEWAY_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_PERFORMANCE_2026-08-14.json)
and passed the exact-revision and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 2 | 108.425 ms | 269.531 ms | 269.531 ms | 269.531 ms |
| Submit to sender `MESSAGE_ACCEPTED` | 200 | 0.583 ms | 1.029 ms | 1.461 ms | 1.592 ms |
| Submit to peer `MESSAGE_PUBLISHED` | 200 | 0.676 ms | 1.155 ms | 1.550 ms | 1.653 ms |

Measured completed-chain throughput was 1,316.596 messages/second. Every
measured message was committed, acknowledged to the sender, published to the
single caught-up peer, and reconciled against the conversation sequence. The
database contained exactly 220 durable messages after 20 warm-ups and 200
measured operations.

## Environment and scenario

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, `chat.v2` Protobuf, and Hikari;
- two Windows-endpoint connections: one sender and one caught-up receiver;
- sequential 256-byte text messages with one live publication per commit;
- Java peak RSS 330,104,832 bytes and observed heap 126,247,864 bytes;
- PostgreSQL postmaster peak RSS 24,363,008 bytes. This is not total database
  process memory.

## Interpretation

This development-host result measures one warm, sequential submit/confirm/fan-
out chain. It is not a concurrent-user, large-group, remote-network, sustained-
duration, or production-capacity result. The two connection-setup samples are
useful only as proof that TLS and Argon2id authentication are included; two
samples are insufficient for a stable authentication latency distribution.

Do not directly compare its throughput with the earlier raw
`PostgresMessageAdapter` baseline. The gateway runtime uses a warm Hikari pool,
while the isolated adapter scenario uses `PGSimpleDataSource`; their connection
management, warm-up, and measured boundaries differ. Future gateway regressions
must use this same gateway scenario and configuration.

This result does not justify Redis, a message broker, multiple gateways, or a
supported-user count. The next M5 evidence must add many conversations and a
parameterized active group so fan-out cost and outbound queue behavior become
visible before topology changes are considered.
