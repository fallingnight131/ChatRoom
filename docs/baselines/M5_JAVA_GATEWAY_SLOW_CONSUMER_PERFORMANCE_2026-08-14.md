# M5 Java V2 Gateway Slow-Consumer Baseline — 2026-08-14

## Result

The five-connection TLS/WSS GROUP scenario isolated one stopped-demand receiver
with zero errors on commit `f8592c4de8f5a4c68c4ccb2c16768ba0dec45023`.
The machine-readable evidence is
[`M5_JAVA_GATEWAY_SLOW_CONSUMER_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_SLOW_CONSUMER_PERFORMANCE_2026-08-14.json)
and passed the schema-4, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 5 | 91.734 ms | 269.215 ms | 269.215 ms | 269.215 ms |
| 64 KiB submit to sender `MESSAGE_ACCEPTED` | 20 | 2.238 ms | 3.305 ms | 3.460 ms | 3.460 ms |
| Submit until all 4 initial peers receive `MESSAGE_PUBLISHED` | 20 | 4.196 ms | 5.784 ms | 6.067 ms | 6.067 ms |
| Slow phase submit until all 3 healthy peers receive publication | 16 | 2.700 ms | 6.955 ms | 6.955 ms | 6.955 ms |
| Post-recovery live probe to all 4 peers | 1 | 2.846 ms | 2.846 ms | 2.846 ms | 2.846 ms |

After the slow receiver stopped requesting WebSocket messages, 16 maximum-size
valid text messages filled its real TLS/socket path enough for the production
gateway write watermark to mark it unwritable and close it. The three healthy
receivers consumed every message, producing exactly 48 verified publications.
The management metric reported exactly one slow-consumer closure.

The client then resumed its original server session with a rotated proof,
recovered all 16 missing conversation sequences in four-message pages, and
received the next live probe together with the healthy receivers. The database
reconciled exactly 42 durable messages: five warm-ups, 20 ordinary measurements,
16 slow-phase messages, and one recovery probe.

## Environment and scenario

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, default 64 KiB/256 KiB write
  watermarks, `chat.v2` Protobuf, Hikari, and the loopback admin metrics endpoint;
- one sender and four authenticated, caught-up Windows-endpoint receivers;
- one receiver stopped JDK WebSocket demand; three remained healthy;
- 65,536-byte `TEXT_UTF8` content, the maximum accepted text payload;
- Java peak RSS 574,603,264 bytes and observed heap 209,522,112 bytes;
- PostgreSQL postmaster peak RSS 24,428,544 bytes. This is not total database
  process memory.

## Interpretation

This proves the production single-gateway behavior end to end: a socket-level
slow consumer does not block durable acceptance or healthy live delivery, is
closed through the existing backpressure action, and can repair every missing
message by authoritative sequence before returning to live delivery.

The observed 16-message closure point is not a portable threshold or production
capacity. It depends on kernel, TLS, JDK client buffering, receiver count, and
host scheduling. The development smoke on the same host closed after 24
messages with fewer healthy receivers, illustrating that variability. The
scenario is bounded, loopback-only, and not a sustained soak or remote-network
test. It exposes a fixed-cardinality closure count, not per-channel pending
bytes, because account/connection labels are intentionally absent from metrics.

This result does not justify Redis, a broker, or multiple gateways. The next M5
work should measure PostgreSQL saturation and transient loss through controlled
pool/queue pressure, verifying fail-closed responses, unaffected connection
lifecycle, recovery, and durable reconciliation before changing topology.
