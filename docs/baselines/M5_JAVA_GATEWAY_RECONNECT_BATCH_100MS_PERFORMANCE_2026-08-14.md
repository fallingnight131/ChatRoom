# M5 Java V2 Gateway 100 ms Batched Reconnect Baseline — 2026-08-14

## Result

Ten TLS/WSS clients completed five session-resume rounds in batches of two,
scheduled 100 ms apart, with zero errors on commit
`c4f2af65a8c01abb4669de716e99522618bd902d`. The machine-readable evidence is
[`M5_JAVA_GATEWAY_RECONNECT_BATCH_100MS_PERFORMANCE_2026-08-14.json`](M5_JAVA_GATEWAY_RECONNECT_BATCH_100MS_PERFORMANCE_2026-08-14.json)
and passed the schema-8, exact-revision, and clean-worktree gates.

| Operation | Samples | P50 | P95 | P99 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| TLS/WSS negotiation plus password authentication | 10 | 91.270 ms | 278.540 ms | 278.540 ms | 278.540 ms |
| Session resume | 50 | 8.878 ms | 12.798 ms | 13.149 ms | 13.149 ms |
| Scheduled-start jitter | 50 | 5.902 ms | 10.051 ms | 10.056 ms | 10.056 ms |

Each round used five batches over a scheduled 400 ms span, an input rate of 10
batches per second. End-to-end measured resume throughput, including the
deliberate pacing, was 23.904 resumes per second. Every connection retained its
exact account, device, and session identity and rotated its resume token before
the next round. The 10 initial authentications plus 50 resumes exactly filled,
but did not weaken, the production 60-attempt direct-peer admission window.

## Comparison and interpretation

The earlier all-at-once run with the same 10 connections and five rounds measured
session-resume P50/P95 of 16.006/24.101 ms. This 100 ms batched run measured
8.878/12.798 ms, roughly 45%/47% lower, while deliberately trading aggregate
completion time for lower instantaneous pressure. The comparison spans commits
and local runs, so it is directional rather than a release threshold.

The measured start jitter reaches about 10 ms. Therefore a client policy must
add randomized jitter and treat intervals as targets, not exact timers. This
single-host result does not establish a production fleet rate or prove behavior
behind a load balancer.

## Environment

- macOS 26.5.2 on arm64, 10 logical processors;
- OpenJDK 21.0.12 and PostgreSQL 17.10 on numeric loopback;
- production `GatewayRuntime`, Netty TLS/WSS, `chat.v2`, session persistence,
  authentication admission control, and Hikari;
- Java peak RSS 745,324,544 bytes and observed heap 359,673,424 bytes;
- PostgreSQL postmaster peak RSS 24,412,160 bytes, not total database memory.

The next comparable curve keeps batch size, connections, rounds, payload, and
authentication budget fixed while shortening only the batch interval to 25 ms.
