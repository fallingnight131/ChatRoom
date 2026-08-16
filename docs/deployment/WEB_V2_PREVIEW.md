# Web V2 Preview Build and Rollback

This is an engineering-preview boundary, not a production cutover. The
supported Web product remains on V1 until migration, operator rehearsal,
observability, UI, and release gates are explicitly accepted.

## Build Configuration

The preview requires three public, compile-time Vite values. Message forwarding
has a fourth independent, default-off value:

```bash
VITE_CHAT_V2_PREVIEW=true \
VITE_CHAT_V2_MESSAGE_FORWARDING=false \
VITE_CHAT_V2_MESSAGE_SEARCH=false \
VITE_CHAT_V2_WSS_URL=wss://preview-chat.example.com/v2/web \
VITE_CHAT_V2_WSS_FALLBACK_URLS='["wss://preview-chat-secondary.example.com/v2/web"]' \
VITE_CHAT_APP_VERSION=2.0.0-preview.1 \
npm run build
```

- `VITE_CHAT_V2_PREVIEW` must be exactly `true`. Missing, empty, `false`, or any
  other spelling keeps V2 disabled.
- `VITE_CHAT_V2_MESSAGE_FORWARDING` is optional and disabled when missing,
  empty, or exactly `false`. Only exact `true` enables the application action
  and requests capability 5; any other spelling invalidates the V2 runtime.
- `VITE_CHAT_V2_MESSAGE_SEARCH` is optional and disabled when missing, empty,
  or exactly `false`. Only exact `true` requests capability 6 and exposes search
  as enabled application state; any other spelling invalidates the V2 runtime.
  The enabled preview exposes bounded in-memory search and separately
  correlated context repair; keep it false outside reviewed candidates.
- `VITE_CHAT_V2_WSS_URL` must use `wss`, contain no credentials/query/fragment,
  and end at the exact `/v2/web` route. It is independent of the user-editable
  V1 host/port settings.
- `VITE_CHAT_V2_WSS_FALLBACK_URLS` is optional JSON containing at most three
  additional unique URLs with the same strict route policy. A malformed,
  duplicated, or insecure entry disables V2 before storage or network access.
  Socket failure rotates through this immutable list; browser-offline events do
  not consume it. Every authority must be present in CSP `connect-src`, gateway
  Origin/Host policy, certificate operations, and monitoring (ADR-0382).
- `VITE_CHAT_APP_VERSION` is a traceable release identifier of at most 64 UTF-8
  bytes.

All `VITE_` values are readable in shipped JavaScript. Never put passwords,
tokens, signing material, or other secrets in them. Configure the gateway's
allowed TLS authority and HTTPS Web Origin separately and consistently.

The composition root is lazy. A preview build emits separate V2 runtime and view
chunks, but it does not open the socket until `/preview/v2` mounts and explicitly
starts the application. Leaving that route stops the background connection.
A normal `npm run build` does not include the inactive V2 runtime in the initial
V1 asset graph.

## Client State

V2 conversation snapshots use the isolated `chat-room-client-v2` IndexedDB
database. The only LocalStorage value is `chat.v2.device-id`, a random non-secret
UUID used as a stable device hint. Login credentials and rotated session-resume
proofs remain in memory. Storage denial falls back to a page-lifetime UUID.
Message edits use a bounded durable command outbox in the same account-scoped
database while keeping authoritative content separate from the optimistic
draft. Reconnect replays the exact operation; a revision conflict preserves the
draft until the user explicitly rebases or discards it.

## Verification and Rollback

Before serving preview assets:

1. run `npm test` and both the default and preview production builds;
   for the deterministic browser boundary, build with
   `VITE_CHAT_V2_WSS_URL=wss://fixture.invalid/v2/web` and run
   `CHATROOM_V2_BROWSER_PREVIEW=true npm run test:browser -- e2e/v2PreviewBrowser.spec.ts`;
2. verify the deployed asset manifest keeps V2 in a separate lazy chunk;
3. verify CSP/connect policy permits only the intended WSS authority;
4. verify the preview gateway Origin, Host, TLS, health, and readiness policy;
5. exercise authentication, reconnect/resume, cache hydration, retry, and safe
   rejection through `/preview/v2` using non-production accounts.
6. switch the browser offline and online; verify the UI reports offline without
   retry churn, then reconnects/resumes promptly without persisting the proof.
7. stop the primary edge while the browser remains online; verify the next
   bounded retry selects a configured fallback, resumes the same page-memory
   session, and repairs ordered history before sending pending work.
8. for a forwarding-enabled candidate, verify the gateway independently has
   `CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED=true`; test authorization,
   rate-limit, offline replay, legacy-client downgrade, and disable either side.
9. for a search-enabled candidate, verify the gateway independently has
   `CHATROOM_GATEWAY_MESSAGE_SEARCH_ENABLED=true`; test membership and group
   lifecycle denial, literal Unicode pagination, edit/recall/deletion current
   state, context non-persistence, late-response abandonment, and disable either
   side.

The deterministic browser boundary uses Playwright WebSocket routing and exact
generated Protobuf envelopes. It verifies the current view/application/transport
composition without opening a real network connection, including a controlled
socket close, memory-only session resume, ordered history repair, and browser
offline/online simulation with a single explicit optimistic-message retry. The
authenticated path also persists low-bandwidth mode and proves text submission
remains available after enablement. It also verifies multi-conversation keyboard
focus is non-activating and that navigation/log landmarks reach the browser
accessibility tree. The device modal additionally verifies contained focus,
Escape restoration, and current-device protection. It does not satisfy the real
TLS, Origin/Host, gateway,
database, physical network, edge-failover, or deployment checks above and must
not be reported as release or capacity evidence.

Use the gateway-first activation and client-first rollback sequence in
[`MESSAGE_FORWARDING_ACTIVATION.md`](MESSAGE_FORWARDING_ACTIVATION.md). A Web
build flag is immutable candidate metadata, not authority to change the gateway.
Use the same gateway-first activation and client-first rollback invariant for
search, with its independent evidence checklist in
[`MESSAGE_SEARCH_ACTIVATION.md`](MESSAGE_SEARCH_ACTIVATION.md).

Rollback by redeploying the prior immutable asset version or a build without the
exact preview flag, then invalidate the HTML entry point according to the Web
release cache policy. Do not delete V1 server data. Isolated V2 browser state can
remain for a later preview or be removed as site data.
