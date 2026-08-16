# M6 Web V2 deterministic browser performance evidence (2026-08-16)

## Result

The opt-in Web V2 client-only performance collector completed 20 isolated
Chromium contexts and the committed schema validator accepted the raw JSON
evidence in
[`M6_WEB_V2_BROWSER_PERFORMANCE_2026-08-16.json`](M6_WEB_V2_BROWSER_PERFORMANCE_2026-08-16.json).

| Client path | P50 | P95 | P99 |
| --- | ---: | ---: | ---: |
| Preview navigation to ready | 90.073 ms | 94.318 ms | 105.775 ms |
| Authentication action to directory | 39.357 ms | 45.883 ms | 50.428 ms |
| Conversation selection to history | 56.340 ms | 69.559 ms | 74.181 ms |
| Send action to accepted state | 35.132 ms | 37.113 ms | 37.437 ms |

These numbers are observations, not pass/fail thresholds.

## Evidence identity

- source revision: `c314a53f6a8f8fcc960872e33d93cf36a95ccfe4`
- compiled client app version: `c314a53f6a8f`
- browser: Playwright Chromium `151.0.7922.34`
- host: macOS/Darwin `25.5.0`, arm64 Apple M4, 10 logical CPUs, 16 GiB
- Node.js: `v23.11.0`
- scenario: generated Protobuf over the in-process deterministic WebSocket
  fixture, one fresh browser context per iteration, no real network

The collector required a clean tree before creating the evidence file and
refused to overwrite an existing result.

## Memory observation

Chromium exposed `performance.memory`, but every context returned the same
coarsened `10,000,000` byte value. The raw observations are retained for audit,
but they are not an exact heap measurement and do not establish a memory
baseline. Release-host tooling with precise memory instrumentation is still
required.

## Reproduction

From `WebClient/`, build the committed V2 preview with the revision-bound app
version, run the single Chromium collector with one worker, and validate the
result:

```bash
env VITE_CHAT_V2_PREVIEW=true \
  VITE_CHAT_V2_WSS_URL=wss://fixture.invalid/v2/web \
  VITE_CHAT_APP_VERSION=c314a53f6a8f \
  npm run build
env CHATROOM_V2_BROWSER_PERFORMANCE=true \
  CHATROOM_V2_PERFORMANCE_OUTPUT=../docs/baselines/M6_WEB_V2_BROWSER_PERFORMANCE_2026-08-16.json \
  npm run test:browser -- e2e/v2PreviewPerformance.spec.ts \
  --project=chromium --workers=1
npm run validate:v2-browser-performance -- \
  ../docs/baselines/M6_WEB_V2_BROWSER_PERFORMANCE_2026-08-16.json
```

The output path must not already exist when reproducing the run; use a new
date- or run-specific filename.

## Claim boundary

This evidence isolates browser composition, rendering, IndexedDB, and client
state-machine cost on one development host. It does not exercise TLS, a real
gateway, PostgreSQL, physical networks, edge failover, supported branded
browsers, or a Windows release host. It is therefore neither a production
capacity claim nor M4 release-performance evidence.
