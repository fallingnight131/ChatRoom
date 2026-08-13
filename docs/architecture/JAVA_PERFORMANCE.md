# Java V2 Performance Baseline

## Purpose

These baselines measure both the canonical Java `PostgresMessageAdapter` and a
bounded production `GatewayRuntime` path against a fresh disposable PostgreSQL
database after all forward migrations. They exist to provide evidence before
M5 introduces Redis routing, a broker, multiple gateways, or database
partitioning. Neither is a production capacity claim and neither may be used to
advertise supported user counts.

The first slice deliberately measures the durable messaging boundary:

- sequential new-message commits;
- stable-key idempotent retry reads;
- concurrent unique commits to one conversation, including sequence locking;
- bounded 100-entry mixed-history reads;
- exact durable message count, maximum sequence, and next-sequence
  reconciliation.

The gateway slices measure one real single-gateway path with bounded connections:

- TLS plus `chat.v2` WebSocket negotiation and password authentication;
- sequential submit-to-`MESSAGE_ACCEPTED` latency;
- submit-to-caught-up-peer `MESSAGE_PUBLISHED` latency;
- completed message/peer-publication throughput;
- concurrent same-session resume with one unique session per connection and
  resume-token rotation on every successful round;
- exact durable message and conversation-sequence reconciliation.

Many conversations, unbounded reconnect storms, slow consumers, Redis,
cross-gateway routing, broker delivery, and dependency failure remain separate
scenarios. Do not infer their behavior from these bounded results.

The disposable PostgreSQL verification now also carries a real-network
correctness gate: `GatewayRuntimePostgresIntegrationTest` starts the production
TLS gateway and completes Windows-endpoint `chat.v2` negotiation, password
authentication, peer history catch-up, message submission, `MESSAGE_ACCEPTED`,
live `MESSAGE_PUBLISHED` fan-out to a second connection, and database
reconciliation through JDK WebSocket clients. This proves the path before it is
timed; it is not yet a load measurement.

## Run

Install PostgreSQL binaries and Java 21, then execute:

```bash
python3 tools/verify_m0.py --java-performance \
  --java-performance-output build/m5/java-v2-postgres-performance.json

python3 tools/verify_m0.py --java-gateway-performance \
  --java-gateway-performance-output build/m5/java-v2-gateway-performance.json
```

The wrapper builds the dedicated `Backend/performance-baseline` module, creates
an isolated trust-authenticated loopback cluster under `/tmp`, applies every
Flyway migration, runs the bounded workload, validates the result, and destroys
the cluster. The Java executable additionally requires
`CHATROOM_PERFORMANCE_CONFIRM=DISPOSABLE_POSTGRES_ONLY` and rejects any JDBC host
other than numeric loopback. Never point it at a shared, staging, or production
database.

The gateway wrapper builds `Backend/gateway-performance-baseline`, creates the
same kind of disposable database plus a one-day localhost certificate, starts
the production Netty gateway in the measured Java process, and drives two JDK
WSS clients through the Windows endpoint. It uses the same confirmation and
numeric-loopback database guard. The generated TLS key and database directory
exist only inside the temporary directory and are deleted on exit.

Defaults are 100 warm-up commits, 500 measured sequential commits, 200 exact
retries, 500 commits at concurrency 8, 200 history reads, and a 256-byte text
payload. Change one input only when recording a new scenario; otherwise later
results are not directly comparable.

Gateway defaults are 20 warm-up messages, 200 measured messages, two
connections, one caught-up receiver per message, and a 256-byte text payload.
The scenario is deliberately sequential so its latency distributions describe
one submit/confirm/fan-out chain; it does not represent concurrent-user load.
The payload parameter is bounded to `1..65536` bytes because this harness sends
`TEXT_UTF8`; larger binary/file content belongs outside the messaging path and
must not be used to force socket pressure. The dedicated slow-consumer scenario
uses the maximum valid text payload rather than inventing an oversized message.

Set `--java-gateway-performance-receivers N` on the unified verifier to create a
real GROUP conversation with one sender and `N` authenticated, caught-up WSS
receivers (`2 <= N <= 59`). The upper bound preserves the production gateway's
default 60-authentications-per-peer window: one sender plus 59 receivers. Group
evidence uses schema 2, records latency until all peers have received each
publication, and reconciles exactly
`messageOperations * N` peer publications. Schema 1 remains the immutable
single-peer contract so dated evidence stays verifiable.

Set `--java-gateway-performance-reconnect-rounds R` (`1 <= R <= 20`) to close
every authenticated connection and concurrently resume its unique server
session in each round. The measured phase starts all clients in a round behind
one barrier, validates the exact account/session/device identity, requires a
new resume token, and carries the rotated token into the next round. Schema 3
records exactly `(N + 1) * R` resume samples, their latency distribution,
aggregate resume throughput, and zero resume errors. It retains the preceding
message/fan-out phase as a correctness preflight and uses no new password login
during the measured resume rounds.

The combined scenario must satisfy `(N + 1) * (R + 1) <= 60`: all clients share
the numeric-loopback source, and the production gateway's default direct-peer
admission window counts the initial password authentication plus every resume.
The harness rejects a larger combination instead of weakening the security
control. For example, 9 receivers and 5 rounds use exactly 60 authentication
attempts (10 initial plus 50 resumes).

This scenario measures successful bounded same-host resumes. It does not inject
network loss, reuse stale tokens, exercise client retry/backoff, or establish a
safe production reconnect rate. Stale-token rejection remains a correctness
gate in the session integration tests rather than a successful-resume latency
sample.

Set `--java-gateway-performance-slow-consumer-max-messages M` (`1 <= M <= 100`)
with at least two receivers to run the separate schema-4 slow-consumer scenario.
The last caught-up receiver stops requesting WebSocket messages while the
sender continues durable submissions and every other receiver must consume each
live publication. The scenario polls the production fixed-cardinality
`live_slow_consumer_closed` metric and fails unless exactly one slow connection
crosses the configured production write watermark within `M` messages. It does
not lower the write watermark or weaken authentication admission.

After closure, the client resumes the same server session with token rotation,
requests every missing sequence in bounded pages, and must receive a final live
probe together with all healthy receivers. Compatible history currently carries
message data in both `messages[]` and ordered `entries[]`, so maximum-size text
recovery uses four messages per page to remain below the 1 MiB V2 envelope
limit. Slow-consumer and reconnect-round modes are mutually exclusive so their
latency and admission effects cannot be conflated.

Use a 65,536-byte payload when deliberately filling a real socket. The exact
message at which closure occurs depends on the host kernel, TLS, and client
receive buffers; it is a recorded observation, not a portable threshold. The
scenario records healthy-peer latency until closure, exact healthy publication
and recovered-history counts, the closure counter, and the post-recovery live
probe. It does not yet record per-channel pending bytes because the production
admin endpoint intentionally exposes no connection labels.

Set `--java-gateway-performance-postgres-saturation-senders S` (`2 <= S <= 16`)
to run the separate schema-5 connection-pool saturation scenario. The disposable
database alone receives a trigger that delays only `saturation-*` benchmark
message inserts for two seconds. The production gateway retains its ordinary
code path but uses an explicit two-connection Hikari pool, one-second connection
timeout, and `S` message workers. `S` separately authenticated WSS senders start
together; an already caught-up peer remains subscribed.

The initial wave must produce both durable acceptances and retryable, redacted
connection-acquisition failures. While both pool connections are occupied,
`/health/ready` must return 503 without closing established WSS sessions. The
harness then removes the temporary trigger, requires readiness to recover to
200, and resubmits every failed operation with its original `clientMessageId`.
The final database sequence set and peer publications must contain exactly one
entry per sender. The temporary function/trigger exists only inside the
throwaway database and is never a migration or runtime failure switch.

This scenario records first-wave response latency, failure/retry counts,
readiness transitions, exact publications, CPU, heap, and RSS. It proves bounded
pool-pressure behavior, not PostgreSQL host loss, database throughput, a safe
pool size, or production capacity. Complete server stop/start recovery remains
a separate failure scenario.

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

`tools/java_gateway_performance_result.py` requires the connection count to
equal one sender plus all receivers, exact setup/accept/all-peer sample counts,
positive completed-chain throughput, zero errors, and no TLS material path.
Schema 2 also requires GROUP identity and exact aggregate peer-publication
count. Schema 3 additionally requires bounded rounds, exact per-connection
resume counts, a monotonic latency distribution, positive resume throughput,
and zero resume errors. Schema 4 requires a bounded closure point, exactly one
slow-consumer action, continuous healthy-peer publications, exact sequence-based
history recovery, and a recovery-probe latency sample. A missing acknowledgement,
publication, session
identity, token rotation, sequence, or durable database reconciliation causes
the Java process to fail before evidence is promoted.
Schema 5 additionally requires a two-connection pool and fixed timeout/delay inputs,
both initial successes and retryable failures, 503-to-200 readiness recovery,
matching converged retries, exact unique publications, and durable sequence
reconciliation.

Results also carry `worktreeDirty`. CI requires a clean tree and exact workflow
revision. A dirty local result remains useful for development comparison but is
not commit-exact evidence and must not be promoted into the dated baseline set.

CI retains the JSON for 14 days but applies no latency or throughput threshold,
because hosted-runner variance would make that gate dishonest. A reviewed dated
baseline may be committed under `docs/baselines/`; comparisons require the same
scenario and should report both absolute distributions and relative change.

## Next M5 measurements

Before selecting distributed infrastructure, extend the harness in this order:

1. many conversations and large active groups;
2. broaden bounded session-resume evidence into controlled reconnect-rate and
   network-failure scenarios;
3. extend slow-consumer evidence with portable pending-byte/backlog observation;
4. extend bounded PostgreSQL pool saturation into complete server stop/start
   recovery and longer contention curves;
5. two gateways, only after an ADR defines reconstructable routing/presence
   ownership and measurements show the single-gateway limitation.

Redis and a broker remain design candidates, not assumed solutions. Each later
scenario needs an operational ownership and rollback plan before it can justify
a new runtime dependency.
