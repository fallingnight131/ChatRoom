# M5 Java V2 Gateway PostgreSQL Outage Baseline — 2026-08-14

## Result

The five-connection TLS/WSS GROUP scenario stopped and restarted its disposable
PostgreSQL process while the production Java gateway and authenticated clients
remained running. It recovered with zero final errors on commit
`d089bde6e4a910e02a5f26c1bcc30a0e994e962e`. The machine-readable evidence is
[`M5_JAVA_GATEWAY_POSTGRES_OUTAGE_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_POSTGRES_OUTAGE_PERFORMANCE_2026-08-14.json)
and passed the schema-6, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 5 | 91.631 ms | 262.892 ms | 262.892 ms | 262.892 ms |
| Ordinary submit to sender `MESSAGE_ACCEPTED` | 20 | 0.948 ms | 1.328 ms | 1.463 ms | 1.463 ms |
| Ordinary submit until all 4 peers receive publication | 20 | 1.285 ms | 1.726 ms | 1.769 ms | 1.769 ms |
| Submit failure after PostgreSQL stopped | 1 | 5.917 ms | 5.917 ms | 5.917 ms | 5.917 ms |
| PostgreSQL start request through readiness and same-ID acceptance | 1 | 307.830 ms | 307.830 ms | 307.830 ms | 307.830 ms |

During the outage, the original authenticated sender received the generic
`messaging is temporarily unavailable` error with `retryable=true`.
`/health/live` remained 200 while `/health/ready` returned 503, and the WSS
connection remained open. After `pg_ctl` restarted the same database,
readiness returned to 200 and the sender retried the identical envelope and
`clientMessageId` on that original socket.

The retry produced one non-duplicate acknowledgement at the next continuous
conversation sequence. Four caught-up receivers each observed one publication,
and PostgreSQL reconciled exactly 26 durable messages: five warm-ups, 20
ordinary measurements, and one recovered operation.

## Environment and scenario

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, `chat.v2`, Hikari, and admin
  liveness/readiness paths;
- a two-connection Hikari pool with a one-second acquisition timeout;
- one sender and four authenticated, caught-up receivers;
- PostgreSQL stopped with fast shutdown and restarted from the same temporary
  data directory by the owning Python wrapper;
- Java peak RSS 458,522,624 bytes and observed heap 210,240,472 bytes;
- PostgreSQL postmaster peak RSS 24,444,928 bytes. This is not total database
  process memory.

## Interpretation

This demonstrates bounded dependency-failure behavior for one gateway: the
process remains live, readiness rejects new load, a stable authenticated socket
receives an explicit retryable error, and Hikari plus the messaging path recover
without restarting the gateway. Stable message identity then converges on one
durable row and one publication per peer.

This loopback result is not an availability SLO, production failover proof, or
safe retry rate. It does not model remote network partitions, repeated restart
cycles, a primary failover, long outages, reconnect storms, or multiple gateway
instances. Those need controlled curves and cross-node ownership decisions
before Redis, a broker, or database topology changes are justified.
