# ADR-0016: V1 Idempotent Attachment Finalization

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

ADR-0013 moved upgraded Web and Windows attachment bytes to an authorized HTTP
data plane, and ADR-0015 made the resulting live notification agree with durable
history. `FILE_UPLOAD_END` still had no explicit result and no retry identity.
If its response or connection was lost, a client could not distinguish a
committed attachment from a failed finalization. Re-uploading could create a
second message and a second stored file.

## Decision

- Upgraded clients allocate one `clientMessageId` (at most 128 UTF-8 bytes) per
  room or friend attachment command and include it in upload start and end.
- Add the compatible `FILE_UPLOAD_END_RSP` response. A successful response
  returns `id`, signed `fileId`, conversation `sequence`, database `timestamp`,
  `clientMessageId`, `uploadId`, and `duplicate`.
- Persist attachment metadata and `clientMessageId` in the same message
  transaction. Existing unique sender/client-message indexes are the durable
  idempotency boundary.
- Retrying the same command returns the original stable result without another
  notification. This lookup also works after the in-memory upload is consumed
  and after process restart.
- Reusing an ID for different room/friend attachment metadata returns
  `CLIENT_MESSAGE_ID_CONFLICT`. A newly uploaded candidate file is deleted on a
  duplicate, conflict, or persistence failure.
- Old clients may omit `clientMessageId`; they retain the previous at-least-once
  behavior and may ignore the additive response. Old-server fallback remains in
  both upgraded clients.

This is idempotent command processing, not exactly-once transport. It does not
yet make legacy inline attachment submission or multi-target forwarding
idempotent.

## Consequences

Web and Windows clients can distinguish accepted, duplicate, and failed upload
finalization and can safely repeat `FILE_UPLOAD_END`. The server keeps accepted,
duplicate, and rejected counters in structured attachment-finalization logs.
The current implementation performs a durable lookup for retries whose upload
state no longer exists; that cost is limited to one indexed sender/key query per
message table.

## Verification and Rollback

The raw HTTP integration suite covers room and friend creation, identical retry,
conflicting reuse, stable ACK fields, signed friend file identity, and retry
after server restart. Windows and Web source contracts require generation and
propagation of `clientMessageId` and handling of `FILE_UPLOAD_END_RSP`.

Rollback removes the new client fields and response handler while leaving the
nullable database values and unique indexes intact. Servers remain compatible
with clients that omit the field.
