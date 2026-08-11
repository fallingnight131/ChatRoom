# ADR-0018: Stream Windows Attachment Downloads over HTTP

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

ADR-0013 moved upgraded Web and Windows uploads out of JSON/Base64, and Web
downloads already preferred the authorized HTTP endpoint. Windows still used
`FILE_DOWNLOAD_REQ` for small files, allocating Base64 in the server and client,
and WebSocket chunks for large files. The two supported clients therefore did
not share the intended “control plane carries metadata, data plane carries
bytes” boundary.

## Decision

- Add a Windows Qt HTTP download adapter using the login-provided HTTP port and
  short-lived file token.
- Preserve the signed V1 file-ID convention and send `friend=1` for negative
  friend-file identities. The server remains authoritative for room/friend
  access and may redirect to a signed COS URL.
- Stream reply chunks into a temporary file rather than accumulating the
  attachment in memory. After success, import the file into the existing
  per-user cache and delete the temporary file.
- Prefer HTTP for every Windows attachment size. If negotiation is unavailable
  or the HTTP request fails, retain the existing Base64/chunk paths solely as an
  old-server compatibility fallback.
- Cancel active HTTP requests with the existing download UI and clean temporary
  files on failure, cancellation, reset, and transport destruction.

## Consequences

Normal Web and Windows attachment bytes now bypass chat JSON/WebSocket frames in
both directions. Windows avoids Base64 expansion and whole-file memory for the
normal path. The current cache import performs a temporary-file copy, which is
safe and bounded but can be optimized to an atomic same-volume move in M2.

Legacy download handlers remain reachable for compatibility, so this decision
does not yet remove their server allocation cost or protocol surface.

## Verification and Rollback

A loopback Qt transport test verifies the signed friend path, token/query
binding, byte integrity, and temporary-file result. The Qt source contract and
Release build require HTTP-first routing. Existing V1 suites continue to cover
legacy fallback and server authorization.

Rollback removes the HTTP preference and adapter; the legacy paths remain wire
compatible. No server or schema rollback is required.
