# ADR-0005: Authorize V1 Resource Access on the Server

- Status: Accepted
- Date: 2026-07-11
- Owners: project maintainers
- Related milestone: M1

## Context

V1 authenticates a socket, but several handlers previously treated client-sent
`roomId`, `fileId`, `messageId`, `friendshipId`, `uploadId`, or username values
as authorization. An authenticated account that guessed an identifier could
read another room's history/member list, persist messages or files into that
room, download attachments, or take over another user's in-flight upload.
Several notification recipients were also selected from client-sent usernames.

The V1 message-type inventory cannot be replaced without breaking the existing
Qt and Web clients, so this security correction must preserve the envelope and
message names.

## Decision

- The authenticated server session is the only source of actor identity.
- Every room data read/write requires a durable `room_members` row. Room
  administration additionally requires an administrator row joined to current
  membership.
- Administrator grants may target only current room members. Room setting
  changes require both room-admin authorization and the existing operator key;
  the key is not a substitute for room authorization.
- A room recall must match both the authenticated sender and the claimed room.
- File access is checked in SQL by joining a room file to current membership or
  a friend file to a friendship participant. This check occurs before local
  reads, COS URL generation, protocol chunking, and HTTP responses.
- Large uploads remain bound to the authenticated user that created the
  server-side upload state. Chunk, finish, and cancel operations reject other
  users, and finalization rechecks current room/friend access.
- Friend acceptance and recall notification recipients are derived from durable
  request/message relationships, not client usernames.
- Denials use existing response message types with optional `success: false` and
  localized `error` fields when a response type exists. Fire-and-forget V1
  messages without a suitable response are dropped and logged with numeric
  resource/user identifiers only.

## Alternatives Considered

- Trust opaque numeric IDs because they are difficult to guess: rejected;
  database row IDs are identifiers, not capabilities.
- Check only the in-memory room manager: rejected because durable membership is
  authoritative and reconnect/offline state is not equivalent to permission.
- Add new V1 denial message types: rejected for this slice because optional
  fields on existing responses preserve current clients while V2 will define
  typed error codes.
- Rely on the developer key for room settings: rejected because an operator
  credential does not establish that the acting account administers a room.

## Consequences

Authorization adds indexed SQLite lookups on protected operations. Correctness
and confidentiality take priority; query-plan/index work remains an M1
performance task. Removing a user from a room or friendship immediately revokes
future file downloads and upload finalization.

Older clients continue to use the same message types and success paths. They may
show only a generic failure when they do not surface the optional error field.
The changes are server-side and require no database migration.

Rollback to a server before this decision reopens known cross-resource access
paths and is not an acceptable production rollback. Roll forward or take the
service out of public reach while repairing a deployment.

## Verification

`Tests/v1_authorization_test.py` runs three authenticated accounts against a
real temporary server and database. It proves that a non-member cannot leave or
administer a room, read members/history, persist a message/file, start or hijack
an upload, recall across rooms, or download a file through TCP chunks or HTTP.
It also verifies authorized message, upload, settings, and download paths.

The existing V1 smoke test remains the positive compatibility gate for room,
friend, reconnect, recall, and file behavior.
