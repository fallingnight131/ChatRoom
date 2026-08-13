# M5 Java V2 Gateway Slow-Consumer Backlog Baseline — 2026-08-14

## Result

The five-connection TLS/WSS GROUP scenario isolated one stopped-demand receiver
with zero errors on commit `5dbeda01985b8fc26916dcf161584540c94e072e`.
The machine-readable evidence is
[`M5_JAVA_GATEWAY_SLOW_CONSUMER_BACKLOG_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_SLOW_CONSUMER_BACKLOG_PERFORMANCE_2026-08-14.json)
and passed the schema-9, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 5 | 95.196 ms | 257.949 ms | 257.949 ms | 257.949 ms |
| 64 KiB submit to sender `MESSAGE_ACCEPTED` | 20 | 2.105 ms | 3.249 ms | 3.828 ms | 3.828 ms |
| Submit until all 4 initial peers receive publication | 20 | 4.152 ms | 5.865 ms | 6.200 ms | 6.200 ms |
| Slow phase submit until all 3 healthy peers receive publication | 16 | 2.560 ms | 3.842 ms | 3.842 ms | 3.842 ms |
| Post-recovery live probe to all 4 peers | 1 | 2.548 ms | 2.548 ms | 2.548 ms | 2.548 ms |

After the slow receiver stopped requesting WebSocket messages, 16 maximum-size
valid text messages crossed the production write watermark. At the exact close
decision Netty reported that 200,253 bytes had to drain before the channel could
become writable. The gateway closed exactly one slow subscriber while three
healthy receivers consumed all 48 slow-phase publications.

The client resumed the original server session with a rotated proof, recovered
all 16 missing sequences in envelope-safe pages, and received the next live
probe with the healthy receivers. The database reconciled exactly 42 durable
messages: five warm-ups, 20 ordinary measurements, 16 slow-phase messages, and
one recovery probe.

## Comparison and interpretation

The earlier schema-4 run with the same shape also closed after 16 messages but
could not report drain bytes. Its slow-phase publication P95 was 6.955 ms; this
run measured 3.842 ms. The runs span commits and local executions, so this is a
directional comparison rather than a regression threshold.

The 200,253-byte value is the amount Netty reported must drain to restore
writability, not total pending bytes. It depends on the configured 64/256 KiB
watermarks, TLS/kernel buffering, runtime, and host scheduling. Neither it nor
the repeated 16-message closure point is a portable production capacity limit.
The useful result is that the production path now supplies a byte-based host
observation while preserving durable acceptance, healthy delivery, closure,
resume, history repair, and restored live delivery.

## Environment

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, default 64 KiB/256 KiB write
  watermarks, `chat.v2`, Hikari, and the loopback metrics endpoint;
- Java peak RSS 552,206,336 bytes and observed heap 350,395,088 bytes;
- PostgreSQL postmaster peak RSS 24,379,392 bytes, not total database memory.

