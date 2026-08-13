# M5 Java V2 Gateway 25 ms Batched Reconnect Baseline — 2026-08-14

## Result

Ten TLS/WSS clients completed five session-resume rounds in batches of two,
scheduled 25 ms apart, with zero errors on commit
`5eaf076bcd205b2c6fd846cf80e536be586b0f90`. The machine-readable evidence is
[`M5_JAVA_GATEWAY_RECONNECT_BATCH_25MS_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_RECONNECT_BATCH_25MS_PERFORMANCE_2026-08-14.json)
and passed the schema-8, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 10 | 81.931 ms | 250.605 ms | 250.605 ms | 250.605 ms |
| Session resume | 50 | 6.448 ms | 9.728 ms | 12.935 ms | 12.935 ms |
| Scheduled-start jitter | 50 | 6.723 ms | 10.011 ms | 10.034 ms | 10.034 ms |

Each round used five batches over a scheduled 100 ms span, an input rate of 40
batches per second. End-to-end measured resume throughput, including deliberate
pacing, was 85.994 resumes per second. Every connection retained its exact
account, device, and session identity and rotated its resume token before the
next round. The 10 initial authentications plus 50 resumes exactly filled, but
did not weaken, the production 60-attempt direct-peer admission window.

Ordinary message traffic in the same run retained 1.140 ms submit-to-accept P95
and 1.795 ms submit-to-all-peers P95 across 180 exact peer publications and 25
durable messages.

## Comparison and provisional guidance

Keeping the scenario shape fixed, the 100 ms curve measured resume P50/P95 of
8.878/12.798 ms and 23.904 resumes per second. This 25 ms curve measured
6.448/9.728 ms and 85.994 resumes per second. Both paced curves kept maximum
scheduled-start jitter near 10 ms and completed without errors. The earlier
all-at-once run measured 16.006/24.101 ms P50/P95, but these comparisons span
commits and local runs and are directional rather than release thresholds.

The existing Web and Windows V2 clients use full-jitter exponential reconnect
with a 500 ms initial ceiling and a 30 second cap. That policy is more
conservative than either local paced curve and should remain unchanged: this
loopback evidence is not a reason to lower the initial ceiling or publish a safe
fleet reconnect rate.

For a future rolling deployment, a gateway should first leave load-balancer
readiness, stop accepting new sessions, and allow a bounded drain period. Any
remaining clients should then reconnect through their existing randomized
backoff instead of being force-disconnected as one fleet-wide burst. The
current single-gateway topology does not yet implement or validate that drain
contract.

## Environment

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, `chat.v2`, session persistence,
  authentication admission control, and Hikari;
- Java peak RSS 599,490,560 bytes and observed heap 317,798,384 bytes;
- PostgreSQL postmaster peak RSS 24,412,160 bytes, not total database memory.

