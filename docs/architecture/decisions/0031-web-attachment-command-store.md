# ADR-0031: Web Attachment Command Store

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M2

## Context

Web attachment uploads currently keep `File`, thumbnail, upload ID, and queue
state only in Pinia memory. Refreshing the page loses the user's intent. Browser
security also means an ordinary file-input selection cannot be silently reopened
after restart.

## Decision

- Add an IndexedDB v3 `attachmentCommands` store partitioned by account and
  stable `clientMessageId`.
- Persist conversation identity, file metadata, content type, a source revision,
  state, timestamps, and an optional structured-cloneable file-system handle.
- Never persist a `File`, `Blob`, thumbnail, upload ID, authorization URL, token,
  or byte offset.
- Recover commands without a persistent handle as `needs_source`; the user must
  reselect a source matching the stored revision.
- Keep permission and source-resolution policy in a transport-neutral
  coordinator. Background recovery may call `queryPermission` but never
  `requestPermission`; permission prompts require an explicit user gesture.
- Remove commands during recovery when the authoritative room/friend list no
  longer grants access to their conversation.
- Do not run access pruning until both authoritative room and friend lists have
  arrived for the active account. Reset the in-memory attachment session on
  logout or account change while retaining the account-partitioned durable
  intent for a later login.
- A recovered command always requests fresh upload authorization and starts
  again at byte zero. This is restartable intent, not byte-range resume.
- Treat persistence as best-effort for a newly selected source: an IndexedDB
  failure disables restart recovery but does not block the current-page upload.
- Remove a command after the durable upload acknowledgement, with the matching
  file notification as an idempotent cleanup fallback. Rejections remain as
  retryable commands.

## Compatibility and Rollback

The new object store is additive. Older Web clients ignore it. Rolling back the
client leaves inert command records; they contain no credentials or file bytes.
Account switching resets live state without exposing one account's commands to
another; access revocation removes durable commands only after both server lists
are authoritative.

## Verification

Unit tests cover identity, source revision, no-handle recovery, cloneable-handle
round trip, account filtering, persistence degradation, permission-denied
recovery, revoked-conversation cleanup, reselection mismatch, and removal. The
Web production build verifies the Pinia upload/ACK integration. User-visible
reselection controls follow as the next slice.
