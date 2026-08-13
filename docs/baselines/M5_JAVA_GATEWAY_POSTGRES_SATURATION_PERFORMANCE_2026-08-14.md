# M5 Java V2 Gateway PostgreSQL Pool-Saturation Baseline — 2026-08-14

## Result

The 13-connection TLS/WSS GROUP scenario saturated a real two-connection Hikari
pool and recovered with zero final errors on commit
`567e77d03e65640a56ba7073838f402c83906573`. The machine-readable evidence is
[`M5_JAVA_GATEWAY_POSTGRES_SATURATION_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_POSTGRES_SATURATION_PERFORMANCE_2026-08-14.json)
and passed the schema-5, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 13 | 83.132 ms | 258.097 ms | 258.097 ms | 258.097 ms |
| Ordinary submit to sender `MESSAGE_ACCEPTED` | 20 | 0.853 ms | 1.355 ms | 2.015 ms | 2.015 ms |
| Ordinary submit until all 4 peers receive publication | 20 | 1.098 ms | 1.738 ms | 2.436 ms | 2.436 ms |
| Saturated first-wave response | 8 | 1,010.776 ms | 4,015.076 ms | 4,015.076 ms | 4,015.076 ms |

Eight separately authenticated senders started one stable-ID submission each.
The two senders that obtained the pool connections committed after the
disposable database's two-second insertion delay. The other six exceeded the
production one-second connection acquisition timeout and received redacted,
retryable protocol errors. During that pressure `/health/ready` returned 503;
the gateway process and established WSS connections remained alive.

After the disposable-only delay trigger was removed, readiness recovered to 200
and all six failed senders retried their original `clientMessageId`. Every retry
converged. The database gained exactly eight messages and eight continuous
sequences, while four caught-up receivers observed exactly 32 unique live
publications. The full scenario reconciled 33 durable messages: five warm-ups,
20 ordinary measurements, and eight saturation operations.

## Environment and scenario

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, `chat.v2`, Hikari, and admin
  readiness/metrics paths;
- two Hikari connections, eight message workers, 16 queued tasks, and one-second
  acquisition timeout;
- eight authenticated senders, four caught-up receivers, and one ordinary
  sender;
- a two-second trigger applied only to `saturation-*` inserts in the throwaway
  database and removed before retries;
- Java peak RSS 549,732,352 bytes and observed heap 140,189,608 bytes;
- PostgreSQL postmaster peak RSS 24,461,312 bytes. This is not total database
  process memory.

## Interpretation

This demonstrates the intended overload behavior: pool pressure withdraws the
gateway from new load, does not kill established sockets, returns explicit
retryable failures rather than blocking indefinitely, and converges safely when
clients retain their stable message identity. The first-wave maximum includes
two serialized two-second insert delays on the same conversation lock; it is
not normal message latency.

The trigger is test infrastructure inside a disposable database, not a product
migration or runtime fault switch. This bounded loopback result does not size a
production pool, measure PostgreSQL throughput, model a remote network, or prove
behavior during complete database process/host loss. A subsequent scenario must
stop and restart PostgreSQL while the gateway stays alive, then verify
readiness, retry semantics, connection recovery, and durable reconciliation.
