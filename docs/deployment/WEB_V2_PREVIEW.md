# Web V2 Preview Build and Rollback

This is an M3 engineering-preview boundary, not a production cutover. The
supported Web product remains on V1 until migration, operator rehearsal,
observability, UI, and release gates are explicitly accepted.

## Build Configuration

The preview requires all three public, compile-time Vite values:

```bash
VITE_CHAT_V2_PREVIEW=true \
VITE_CHAT_V2_WSS_URL=wss://preview-chat.example.com/v2/web \
VITE_CHAT_APP_VERSION=2.0.0-preview.1 \
npm run build
```

- `VITE_CHAT_V2_PREVIEW` must be exactly `true`. Missing, empty, `false`, or any
  other spelling keeps V2 disabled.
- `VITE_CHAT_V2_WSS_URL` must use `wss`, contain no credentials/query/fragment,
  and end at the exact `/v2/web` route. It is independent of the user-editable
  V1 host/port settings.
- `VITE_CHAT_APP_VERSION` is a traceable release identifier of at most 64 UTF-8
  bytes.

All `VITE_` values are readable in shipped JavaScript. Never put passwords,
tokens, signing material, or other secrets in them. Configure the gateway's
allowed TLS authority and HTTPS Web Origin separately and consistently.

The composition root is lazy. A preview build emits a separate V2 chunk, but it
does not open the socket until a preview UI explicitly starts the application.
A normal `npm run build` does not include the inactive V2 runtime in the initial
V1 asset graph.

## Client State

V2 conversation snapshots use the isolated `chat-room-client-v2` IndexedDB
database. The only LocalStorage value is `chat.v2.device-id`, a random non-secret
UUID used as a stable device hint. Login credentials and rotated session-resume
proofs remain in memory. Storage denial falls back to a page-lifetime UUID.

## Verification and Rollback

Before serving preview assets:

1. run `npm test` and both the default and preview production builds;
2. verify the deployed asset manifest keeps V2 in a separate lazy chunk;
3. verify CSP/connect policy permits only the intended WSS authority;
4. verify the preview gateway Origin, Host, TLS, health, and readiness policy;
5. exercise authentication, reconnect/resume, cache hydration, retry, and safe
   rejection using non-production accounts once the preview UI exists.

Rollback by redeploying the prior immutable asset version or a build without the
exact preview flag, then invalidate the HTML entry point according to the Web
release cache policy. Do not delete V1 server data. Isolated V2 browser state can
remain for a later preview or be removed as site data.
