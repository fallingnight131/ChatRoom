# M5 Java V2 Gateway Session-Resume Baseline — 2026-08-14

## Result

The 10-connection TLS/WSS scenario completed five concurrent same-session
resume rounds with zero errors on commit
`159240cf787c732fcf9aea7358eb8443d510f501`. The machine-readable evidence is
[`M5_JAVA_GATEWAY_RECONNECT_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_RECONNECT_PERFORMANCE_2026-08-14.json)
and passed the schema-3, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 10 | 86.512 ms | 242.825 ms | 242.825 ms | 242.825 ms |
| Submit to sender `MESSAGE_ACCEPTED` | 20 | 0.898 ms | 1.563 ms | 1.576 ms | 1.576 ms |
| Submit until all 9 peers receive `MESSAGE_PUBLISHED` | 20 | 1.383 ms | 2.115 ms | 2.532 ms | 2.532 ms |
| TLS/WSS reconnect plus `RESUME_SESSION` | 50 | 16.006 ms | 24.101 ms | 24.590 ms | 24.590 ms |

Measured aggregate session-resume throughput was 494.5 successful resumes per
second. Every resumed connection retained the exact account, device, and
session identity and received a newly rotated resume token before the next
round. The message preflight also produced exactly 180 peer publications and
the database contained exactly 25 durable messages after five warm-ups.

## Environment and scenario

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, `chat.v2` Protobuf, and Hikari;
- one GROUP sender and nine authenticated, caught-up Windows-endpoint receivers;
- five barrier-started resume rounds, giving 50 measured resume operations;
- 10 initial authentications plus 50 resumes, exactly filling rather than
  weakening the default 60-attempt direct-peer admission window;
- Java peak RSS 742,391,808 bytes and observed heap 329,844,144 bytes;
- PostgreSQL postmaster peak RSS 24,444,928 bytes. This is not total database
  process memory.

## Interpretation

This result establishes a reproducible bounded successful-resume comparison
point. It is not a safe production reconnect rate or a supported-user count.
All clients use loopback, the rounds contain no jitter or sustained arrival
rate, and the scenario does not inject packet loss, dependency failure, stale
tokens, client retry/backoff, or history catch-up after a prolonged outage.

Using the entire authentication window deliberately proves that the harness
honors the production admission control, but an operational deployment needs
headroom and reconnect-storm controls. The comparatively high Java RSS also
includes ten separate JDK `HttpClient` instances and the in-process gateway;
it is a whole-scenario observation, not per-session memory.

This evidence still does not justify Redis, a broker, or multiple gateways. The
next M5 slice should make slow or unwritable consumer behavior measurable,
including outbound queue bounds, disconnect action, healthy-peer isolation,
and durable recovery after the slow connection is removed.
