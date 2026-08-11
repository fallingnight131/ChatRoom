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
- A recovered command always requests fresh upload authorization and starts
  again at byte zero. This is restartable intent, not byte-range resume.

## Compatibility and Rollback

The new object store is additive. Older Web clients ignore it. Rolling back the
client leaves inert command records; they contain no credentials or file bytes.
Account/logout and access-revocation cleanup must be integrated before the
feature is considered complete.

## Verification

Unit tests cover identity, source revision, no-handle recovery, cloneable-handle
round trip, account filtering, and removal. UI/reconnect orchestration and
browser permission tests follow as the next slice.
