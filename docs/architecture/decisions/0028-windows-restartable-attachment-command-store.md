# ADR-0028: Windows Restartable Attachment Command Store

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M2

## Context

Windows text and emoji commands survive a process restart, but attachment state
still lives only in `ChatWindow`. A crash after file selection can therefore
lose the user's intent. The V1 server makes final message creation idempotent by
`clientMessageId`, while its upload session and HTTP PUT are process-local and
do not support byte-range continuation.

A durable attachment command contains a local source path and must not turn an
expired upload authorization into a persistent secret. It must also detect the
ordinary case where the source file was replaced before a recovery attempt.

## Decision

- Upgrade the Windows account-local SQLite store to schema version 2 and add an
  account/conversation-scoped `attachment_outbox` table.
- Persist the stable `clientMessageId`, source path, display name, content type,
  expected size and modification time, a bounded source fingerprint, lifecycle
  state, transmitted byte count, and a stable failure code.
- Never persist the short-lived HTTP bearer token, server upload ID, upload
  URL, thumbnail bytes, or an open file handle.
- Treat `uploading` and `finalizing` as observations of the previous process,
  not resumable server sessions. Recovery keeps the same `clientMessageId`,
  validates the source revision, asks the server for fresh authorization, and
  retransmits from byte zero.
- Cascade commands when an authoritative room/friend eviction removes the
  conversation. Preserve commands during ordinary cache clearing and copy them
  during an account-identity migration.
- Keep failed commands durable for explicit retry or cancellation.
- Use the transport-neutral `AttachmentOutboxService` to create and validate
  source fingerprints, gate recovery on authoritative room/friend membership,
  normalize stale transport state, and own terminal cleanup. UI projection and
  V1 dispatch remain at the Windows adapter boundary.
- The Windows adapter serializes attachment commands because the legacy upload
  protocol has one active session. It stages intent before requesting upload
  authorization, revalidates immediately before dispatch, resets interrupted
  sessions to fresh authorization after an authoritative room/friend list, and
  removes durable state on authoritative notification, final ACK, or explicit
  cancellation.

## Consequences

The local model can now distinguish durable attachment intent from ephemeral
transport state. It does not claim range-resume support, and a restart may
repeat attachment bytes, but final message creation remains duplicate-safe on
an upgraded V1 server. Local source paths are privacy-sensitive data and are
removed on completion, cancellation, or loss of conversation authorization.

## Migration and Rollback

Initialization performs an additive schema-1-to-schema-2 migration inside one
transaction. Older binaries reject schema 2 as newer than supported; rollback
therefore requires restoring the pre-upgrade local cache or deleting only the
rebuildable account cache after preserving user drafts and unresolved intent.
The server and wire protocol are unchanged.

## Verification

- repository tests cover schema-1 migration, restart restoration, state and
  progress updates, cache-clear preservation, account isolation and copy, and
  authorization-eviction cascade;
- schema inspection verifies that upload tokens and upload IDs are absent;
- application-service tests cover membership-gated recovery, current peer-name
  resolution, stable-ID reuse, stale-progress normalization, source replacement,
  explicit retry validation, completion, and cancellation;
- the Qt source gate locks room/direct composer paths to the durable outbox and
  the Qt Release gate compiles the serialized V1 adapter;
- future-schema rejection remains covered.
