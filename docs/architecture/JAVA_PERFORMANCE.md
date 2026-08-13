# Java V2 Performance Baseline

## Purpose

This baseline measures the canonical Java `PostgresMessageAdapter` against a
fresh disposable PostgreSQL database after all forward migrations. It exists to
provide evidence before M5 introduces Redis routing, a broker, multiple
gateways, or database partitioning. It is not an end-to-end gateway capacity
claim and must not be used to advertise supported user counts.

The first slice deliberately measures the durable messaging boundary:

- sequential new-message commits;
- stable-key idempotent retry reads;
- concurrent unique commits to one conversation, including sequence locking;
- bounded 100-entry mixed-history reads;
- exact durable message count, maximum sequence, and next-sequence
  reconciliation.

TLS/WebSocket framing, authentication hashing, live fan-out, large groups,
reconnect storms, slow consumers, Redis, cross-gateway routing, broker delivery,
and dependency failure remain separate scenarios. Do not infer their behavior
from this persistence result.

The disposable PostgreSQL verification now also carries a real-network
correctness gate: `GatewayRuntimePostgresIntegrationTest` starts the production
TLS gateway and completes Windows-endpoint `chat.v2` negotiation, password
authentication, message submission, `MESSAGE_ACCEPTED`, and database
reconciliation through a JDK WebSocket client. This proves the path before it is
timed; it is not yet a load measurement.

## Run

Install PostgreSQL binaries and Java 21, then execute:

```bash
python3 tools/verify_m0.py --java-performance \
  --java-performance-output build/m5/java-v2-postgres-performance.json
```

The wrapper builds the dedicated `Backend/performance-baseline` module, creates
an isolated trust-authenticated loopback cluster under `/tmp`, applies every
Flyway migration, runs the bounded workload, validates the result, and destroys
the cluster. The Java executable additionally requires
`CHATROOM_PERFORMANCE_CONFIRM=DISPOSABLE_POSTGRES_ONLY` and rejects any JDBC host
other than numeric loopback. Never point it at a shared, staging, or production
database.

Defaults are 100 warm-up commits, 500 measured sequential commits, 200 exact
retries, 500 commits at concurrency 8, 200 history reads, and a 256-byte text
payload. Change one input only when recording a new scenario; otherwise later
results are not directly comparable.

## Evidence contract

`tools/java_performance_result.py` requires:

- exact schema and benchmark identity plus a lowercase source revision;
- UTC start/record timestamps and explicit non-capacity warning;
- Java, OS, architecture, processor, CPU, heap, Java RSS, and PostgreSQL
  postmaster RSS observations (not total database-process memory);
- complete scenario counts and exact durable sequence reconciliation;
- monotonic min/P50/P95/P99/max distributions with exact sample counts;
- positive sequential/concurrent throughput and zero concurrent errors;
- no JDBC URL, password, token, session, or account identity.

Results also carry `worktreeDirty`. CI requires a clean tree and exact workflow
revision. A dirty local result remains useful for development comparison but is
not commit-exact evidence and must not be promoted into the dated baseline set.

CI retains the JSON for 14 days but applies no latency or throughput threshold,
because hosted-runner variance would make that gate dishonest. A reviewed dated
baseline may be committed under `docs/baselines/`; comparisons require the same
scenario and should report both absolute distributions and relative change.

## Next M5 measurements

Before selecting distributed infrastructure, extend the harness in this order:

1. full TLS/WSS gateway submit-to-accept and live fan-out;
2. many conversations and large active groups;
3. reconnect storms and session resume;
4. slow/unwritable consumers and bounded queue behavior;
5. PostgreSQL saturation and transient dependency failure;
6. two gateways, only after an ADR defines reconstructable routing/presence
   ownership and measurements show the single-gateway limitation.

Redis and a broker remain design candidates, not assumed solutions. Each later
scenario needs an operational ownership and rollback plan before it can justify
a new runtime dependency.
