# ADR-0014: V1 Server-Side Attachment Forwarding

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

The Windows client forwarded cached attachments by reading the complete local
file and embedding Base64 bytes in one `FILE_SEND` or `FRIEND_FILE_SEND` command
for every destination. This required a local download, duplicated network and
JSON allocation work, was capped at 8 MiB, and trusted the client to resubmit
content the server already stored. Composer uploads no longer use that path
after ADR-0013, so upgraded multi-target forwarding must also leave attachment
bytes out of the chat control plane.

V1 stores a separate file row and path per room or friendship. It does not yet
have the immutable blob identity and reference accounting required for safe
zero-copy forwarding across conversations.

## Decision

Add an optional V1 server-side forwarding command:

- A successful login advertises `serverFileForward: true`. Upgraded Windows
  clients use the new command only when the server advertises it; otherwise the
  bounded cached-file Base64 path remains as an old-server fallback.
- `FILE_FORWARD_REQ` carries the signed source file ID plus unique room IDs and
  friend usernames. It never carries original file bytes.
- The server verifies the authenticated user can still access the non-cleared
  source file. It then independently verifies room membership or friendship for
  every destination; client target lists are not authority.
- Each successful destination receives its own copied file row, message row,
  file ID, and existing `FILE_NOTIFY` or `FRIEND_FILE_NOTIFY`. Room file quota is
  reserved and released through the existing mechanism.
- `FILE_FORWARD_RSP` returns aggregate success/failure counts and a result for
  each destination. Partial success is explicit rather than rolled back after
  already-visible notifications.
- V1 performs bounded synchronous local copies: at most an 8 MiB source and ten
  unique destinations per request. This prevents the single-node event loop
  from accepting unbounded copy work. Larger forwarding waits for asynchronous
  storage work or shared immutable blob identity.

## Alternatives Considered

- Re-upload the local file once per destination over HTTP: rejected because it
  repeats network transfer and storage ingress for bytes already authorized on
  the server.
- Reuse one physical path from multiple file rows: rejected because current
  retention and recall delete paths assume path ownership and have no reference
  count, which could invalidate other conversations.
- Add shared blob tables now: deferred to the V2/PostgreSQL attachment model so
  M1 remains a reversible V1 compatibility slice.
- Remove the old-server fallback immediately: rejected because an upgraded
  Windows client must remain usable while server rollout is incomplete.

## Consequences

Normal upgraded forwarding sends only bounded metadata and works without a
local cached copy. Source and destination authorization are centralized, and
the UI can report partial failures. V1 still duplicates local file bytes and
cannot forward files above 8 MiB; no scale claim is made. V2 should separate
immutable blob identity from conversation attachment references and perform any
physical copy asynchronously.

## Verification and Rollback

Integration coverage must verify room and friend targets, byte integrity after
download, distinct destination file IDs, notifications without `fileData`,
foreign-source denial, unauthorized-target denial, target-count bounds, and
partial result reporting. The Windows source contract must reject inline bytes
from the advertised-capability path.

Rollback stops advertising `serverFileForward`; upgraded clients then use the
existing bounded compatibility fallback. The additive message types remain
ignored by older clients.
