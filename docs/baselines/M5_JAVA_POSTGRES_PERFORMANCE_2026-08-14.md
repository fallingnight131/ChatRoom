# M5 Java V2 PostgreSQL Messaging Baseline — 2026-08-14

## Result

The default durable-messaging scenario completed with zero concurrent errors on
commit `4e70de5a6c97275d8a050156cddcf4a90fd93c2e`. The machine-readable evidence is
[`M5_JAVA_POSTGRES_PERFORMANCE_2026-08-14.json`](M5_JAVA_POSTGRES_PERFORMANCE_2026-08-14.json)
and passed the exact-revision and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| Sequential commit | 500 | 3.689 ms | 4.405 ms | 4.823 ms | 5.903 ms |
| Idempotent retry | 200 | 2.979 ms | 3.932 ms | 6.316 ms | 9.755 ms |
| Same-conversation concurrent commit | 500 | 9.479 ms | 18.171 ms | 23.355 ms | 28.058 ms |
| 100-entry history read | 200 | 5.356 ms | 6.407 ms | 7.392 ms | 9.439 ms |

Measured throughput was 265.756 sequential commits/second and 753.899
same-conversation concurrent commits/second at concurrency 8. The database
reconciled 1,101 durable messages with no sequence gaps detected by the harness.

## Environment and scenario

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12, PostgreSQL 17.10 on numeric loopback;
- a fresh disposable cluster with all 49 Flyway migrations;
- 100 warm-up commits, 256-byte payloads, and a 100-message history page;
- Java peak RSS 305,496,064 bytes and observed heap 112,084,296 bytes;
- PostgreSQL postmaster peak RSS 24,395,776 bytes. This is not total database
  process memory.

## Interpretation

This is a development-host persistence baseline, not a gateway or production
capacity result. It excludes TLS/WSS framing, authentication, fan-out, group
membership expansion, reconnects, slow consumers, network latency, and
dependency failure. It therefore does not justify a supported-user count,
Redis, a message broker, or multi-gateway deployment.

Use this result as the comparison point for changes to the PostgreSQL messaging
adapter when the scenario remains identical. The next M5 evidence must measure
the full single-gateway TLS/WSS path before a topology decision is recorded.
