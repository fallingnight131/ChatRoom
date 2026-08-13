# M5 Java V2 Gateway 100 Active Conversations Baseline — 2026-08-14

## Result

Five authenticated TLS/WSS connections retained 400 live routing subscriptions
at the production per-channel bound of 100 active GROUP conversations with zero
errors on commit `bd7e30a970bd2e4024015704215739a41d9d615b`. The machine-readable evidence is
[`M5_JAVA_GATEWAY_ACTIVE_CONVERSATIONS_100_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_ACTIVE_CONVERSATIONS_100_PERFORMANCE_2026-08-14.json)
and passed the schema-7, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 5 | 87.871 ms | 234.049 ms | 234.049 ms | 234.049 ms |
| Per-receiver activation of all 100 conversations | 4 | 42.846 ms | 87.599 ms | 87.599 ms | 87.599 ms |
| Submit to sender `MESSAGE_ACCEPTED` | 100 | 0.468 ms | 0.924 ms | 1.456 ms | 1.690 ms |
| Submit until all 4 peers receive publication | 100 | 0.574 ms | 1.060 ms | 1.645 ms | 1.791 ms |

The workload placed one warm-up and one measured message in every conversation.
PostgreSQL reconciled exactly two continuous messages per conversation, 200
total; four receivers observed exactly 400 measured live publications. The
seeded model contained 500 memberships and the process-local router retained
exactly 400 receiver subscriptions.

## Comparison and architecture implication

The comparable 10-conversation run activated all routes at 7.786 ms P50 and
28.080 ms P95; the 100-conversation run measured 42.846 ms P50 and 87.599 ms
P95. A tenfold route count increased median activation about 5.5 times on this
host. Ordinary four-peer publication P95 remained 1.060 ms versus 1.483 ms;
that lower value is normal local-run variance, not evidence that more routes
improve performance.

The bounded process-local map is therefore adequate for the measured single
gateway scope and does not yet justify Redis or a broker. The hard limitation is
semantic, not this latency curve: routes are lost with the gateway process and
cannot reach clients on another gateway. A multi-gateway design still needs an
ADR for lease ownership, reconstruction, failure, and rollback before adding a
distributed dependency.

## Environment and limits

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- Java peak RSS 450,265,088 bytes and observed heap 213,534,696 bytes;
- PostgreSQL postmaster peak RSS 24,395,776 bytes, not total database memory.

This is not a supported-user count or capacity limit. Five local connections,
small payloads, sequential submissions, and one gateway do not represent a
production fleet, reconnect storm, or remote database. Group fan-out growth is
measured separately because receiver count and conversation count have
different costs.
