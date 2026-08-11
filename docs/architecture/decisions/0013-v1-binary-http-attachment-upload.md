# ADR-0013: V1 Binary HTTP Attachment Upload

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

V1 inline files and large-file chunks Base64-encode bytes inside JSON carried by
TCP/WebSocket. This expands payloads, allocates repeated full-size buffers, and
lets attachment throughput compete with chat control/message traffic. The
server already has authorized upload sessions, quota reservation, local file
persistence, optional COS replication, HTTP downloads, and final attachment
notification. Replacing that domain flow is unnecessary for the M1 correction.

## Decision

Extend the existing upload-session flow with a raw binary HTTP transport:

- `FILE_UPLOAD_START` and `FRIEND_FILE_UPLOAD_START` remain the authenticated
  control-plane operations. A successful response advertises an additive HTTP
  upload URL for clients that support it.
- The URL names the opaque upload ID and carries the existing short-lived login
  file token. The server accepts it only when the token user owns that upload
  session. Upload IDs alone are not credentials.
- The data plane uses one `PUT` with a required, exact `Content-Length`; chunked
  transfer encoding and over/under-sized bodies are rejected. Bytes are
  streamed to the existing temporary file with bounded memory, never assembled
  into a JSON or Base64 value.
- Finalization reuses the current authorization recheck, database metadata,
  notification, quota release, retention, and optional COS replication paths.
  A failed/disconnected upload removes partial bytes and releases reservations.
- Web uses `fetch`/XHR behind a browser upload adapter. Windows Qt uses
  `QNetworkAccessManager` behind its transport layer. UI code does not construct
  server paths or tokens.
- Web room/friend and Windows composer uploads use this adapter. Windows
  multi-target forwarding now uses the server-side identity command in
  ADR-0014. The old Base64 inline and chunk messages remain temporarily for
  old-client/old-server compatibility and are instrumented before removal; new
  clients do not silently fall back after an authorization or integrity
  failure.
- Downloads, avatars, and thumbnails are separate paths. Thumbnail metadata may
  remain a bounded Base64 field while original attachment bytes use HTTP.

The V2 target remains direct object-storage upload with short-lived scoped
authorization. The V1 HTTP path is a compatibility bridge that removes bytes
from the chat protocol without making the Qt/C++ application server the final
large-scale storage architecture.

## Alternatives Considered

- Immediately implement provider-specific browser-to-COS signing: rejected for
  this slice because local-storage deployments and test environments also need
  a safe path, and upload authorization must not depend on one vendor.
- Keep Base64 chunks and only reduce their size: rejected because expansion,
  allocation, and control-plane contention remain.
- Put raw bytes in a new WebSocket binary frame: rejected because upload
  backpressure and message routing would still share the chat gateway.
- Remove old upload messages immediately: rejected because it would break the
  documented V1 compatibility window.

## Consequences

Attachment bytes stop consuming JSON frame capacity for upgraded clients and
can use standard HTTP proxy limits and observability. The single-node server
still receives and writes bytes, so this is not a horizontal-scale claim.
Production must terminate TLS, restrict CORS to owned Web origins, bound
concurrent uploads, expire abandoned sessions/tokens, and avoid logging tokens
or filenames.

## Verification and Rollback

Integration tests must cover authorized exact upload, foreign/expired token,
unknown upload ID, missing/incorrect length, oversized body, disconnect cleanup,
membership/friendship revoked before finalization, notification metadata, and
the absence of attachment Base64 in upgraded client frames. Slow-reader/writer
tests must demonstrate bounded memory.

Rollback keeps the additive response fields ignored and leaves the existing
Base64 handlers available to old clients. Partially uploaded files and quota
reservations must be cleaned before disabling the HTTP route.
