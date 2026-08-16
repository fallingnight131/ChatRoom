# V2 Message Search Activation

Status: default-off M6 release contract for Web and Windows. Candidate compile
gates are not production evidence. Retain the exact environment, revision,
authorization, observability, and rollback evidence for every canary. The
invariant is gateway-first activation and client-first rollback.

## Independent gates

| Surface | Exact gate | Activation boundary |
| --- | --- | --- |
| Java gateway | `CHATROOM_GATEWAY_MESSAGE_SEARCH_ENABLED=true` | process startup and each new V2 handshake |
| Web | `VITE_CHAT_V2_MESSAGE_SEARCH=true` | immutable Vite candidate build |
| Windows | `CHATROOM_ENABLE_WINDOWS_V2_SEARCH=ON` | immutable CMake Release build |

All gates default to disabled and malformed values fail closed. Windows also
requires `CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON`. A client requests capability
6 only when its build gate is enabled; the server advertises capability 6 only
when its own policy is enabled and the client requested it. Existing
connections do not renegotiate after a gateway configuration change.

## Activation order

1. Deploy the reviewed gateway and PostgreSQL schema with search disabled.
   Confirm readiness, ordinary message history, and clients requesting only
   their previously enabled capabilities.
2. Enable the gateway flag on a canary instance and restart it. Keep both client
   gates disabled. Confirm only new capable test sessions can negotiate
   capability 6.
3. With non-production accounts, verify active-member authorization, removed
   member denial, closed-group denial, literal `%`/`_`, Unicode, descending
   pagination, edit replacement, and recall/deletion exclusion. Retain bounded
   query-plan and worker/connection-pool observations; they are not capacity
   claims.
4. Build immutable Web and Windows candidates with their exact search gates.
   Prove the ordinary candidates remain off. Verify keyboard and screen-reader
   operation, query/result memory bounds, reconnect clearing, and that opening
   an uncached result never persists search results or partial context windows
   or advances the ordinary synchronization cursor.
5. Promote the Web candidate to a preview route, then a signed Windows Release
   candidate. Canary the `/v2/web` and `/v2/windows` endpoints independently and
   retain their asset/binary identities and negotiated handshakes.
6. Observe fixed-label search success, denial, saturation, protocol failure,
   PostgreSQL latency/pool pressure, reconnect behavior, and support feedback
   before any wider promotion.

Do not enable a client before its gateway endpoint. The client must fail closed
when capability 6 is omitted, but that protection makes the preview unavailable
rather than providing a fallback search implementation.

## Local Web candidate evidence

The deterministic generated-Protobuf browser fixture can prove the immutable
Web candidate seam before an endpoint canary. Build and run the enabled
candidate from `WebClient/`:

```bash
env VITE_CHAT_V2_PREVIEW=true \
  VITE_CHAT_V2_MESSAGE_SEARCH=true \
  VITE_CHAT_V2_WSS_URL=wss://fixture.invalid/v2/web \
  VITE_CHAT_APP_VERSION=<candidate-version> \
  npm run build
env CHATROOM_V2_BROWSER_PREVIEW=true \
  CHATROOM_V2_BROWSER_SEARCH=true \
  npm run test:browser -- e2e/v2PreviewBrowser.spec.ts
```

Then rebuild the same revision with
`VITE_CHAT_V2_MESSAGE_SEARCH=false` and run the rollback candidate:

```bash
env CHATROOM_V2_BROWSER_PREVIEW=true \
  CHATROOM_V2_BROWSER_SEARCH_ROLLBACK=true \
  npm run test:browser -- e2e/v2PreviewBrowser.spec.ts
```

The enabled path verifies capability-gated type-126/type-127 exchange, bounded
query shape, live result announcement, accessibility-tree semantics, keyboard
result activation, and one correlated context read. A controlled socket restart
also proves results are cleared, resume does not automatically replay a query,
and the retained editor text is sent again only after explicit user action. The
rollback path verifies that the search entry point and type-126 command are both
absent. Application tests separately prove stale-response isolation,
result/context non-persistence, and cursor isolation.

This fixture opens no real network connection. It does not prove gateway
configuration, TLS/WSS, PostgreSQL authority, deployed asset identity,
observability, endpoint canary behavior, or public rollback. Keep the real Web
endpoint activation item open until those retained records exist.

## Rollback order

1. Stop promotion and restore Web and Windows candidates whose search gates are
   disabled. Do not delete ordinary message caches, drafts, or outboxes.
2. Wait for or terminate the bounded capable client sessions according to the
   incident plan. Confirm new sessions no longer request capability 6.
3. Set the gateway flag to exact `false` and restart instances gradually. New
   handshakes must omit capability 6; existing negotiated sessions retain it
   until disconnected, which is why client-first rollback is required.
4. Preserve the PostgreSQL schema and ordinary history indexes. Search has no
   separate durable result store or external index to roll back in this phase.

## Required release evidence

- exact gateway revision and sanitized configuration digest;
- immutable Web asset and signed Windows binary identities plus public gates;
- capable Web and Windows handshakes and old-client downgrade behavior;
- membership/lifecycle denial and edit/recall/deletion current-state results;
- literal Unicode and pagination tests, reconnect/late-response abandonment,
  and context non-persistence proof;
- fixed-label metrics and bounded PostgreSQL observations before, during, and
  after each endpoint canary;
- exact client-first rollback rehearsal and post-rollback handshakes.

Never include credentials, user IDs, conversation IDs, search text, message
content, signing keys, or production endpoints in retained evidence.
