# ADR-0024: Windows Idempotent Optimistic Outbox

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M2

## Context

The Windows client previously cleared the composer after writing directly to
the socket and rendered only the later server broadcast. A disconnect between
those steps could leave the user with no visible message and no restart-safe
retry intent. V1 room and direct text submission already accepts a stable
`clientMessageId` and returns the authoritative ID, sequence, and timestamp.

## Decision

- Render room/direct text and emoji messages immediately with a client-generated
  stable ID and local `sending` state.
- Persist unresolved messages inside the bounded conversation snapshot. Rows
  with no server ID, a client ID, and `sending` state form the first Windows
  outbox; no second copy of message content is introduced.
- Retry unresolved room sends only after the authenticated room list confirms
  access. Retry direct sends only after the friend list maps the durable
  `friendshipId` conversation key to the current username.
- Reuse the original client ID for automatic and manual retry. A successful ACK,
  duplicate ACK, live echo, or history page reconciles the optimistic row in
  place. A server rejection becomes `failed` and is not automatically retried.
- Expose `sending` and `failed` beside the timestamp and offer explicit retry
  for failed text/emoji messages.
- Keep attachment upload commands out of this slice. Their file handles,
  authorization expiry, byte offsets, and cleanup need a separate restartable
  command model.

## Consequences

An app restart or transient disconnect no longer loses accepted text intent,
and retries cannot create duplicate durable messages on upgraded V1 servers.
Relationship/membership eviction removes the optimistic rows with the rest of
the inaccessible conversation. Snapshot replacement remains synchronous and
bounded; later extraction must move orchestration and high-volume writes away
from `ChatWindow` without changing these semantics.

## Rollback

Older clients ignore the local-only `deliveryState` JSON field. Rolling back the
UI stops retrying unresolved rows; the server remains authoritative and history
restores accepted messages. No server schema or wire-version rollback is needed.

## Verification

- model tests cover client-ID replacement, failed state, and authoritative ACK
  promotion;
- repository tests cover restart discovery of `sending` rows and exclusion of
  `failed` rows from automatic retry;
- the Qt gate builds both supported-client paths and runs reconnect/model/store
  regression tests on the macOS development host;
- native Windows behavior remains covered by Windows Release CI and later M4
  clean-host gates.
