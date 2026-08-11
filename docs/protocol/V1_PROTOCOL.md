# V1 Protocol Baseline

Status: active compatibility protocol at M0.

Authoritative declaration: `Common/Protocol.h`.

The machine-readable inventory records 122 declared message types and 50 message
types explicitly dispatched from client to server. Run
`python3 tools/m0_inventory.py --check` after changing protocol or dispatch code.

## Envelope

Every current message is a JSON object:

```json
{
  "type": "CHAT_MSG",
  "id": "message-envelope-uuid",
  "timestamp": 1738000000000,
  "data": {}
}
```

- `type`: string constant from `Protocol::MsgType`.
- `id`: a new UUID created by `makeMessage`; it is not consistently the durable
  database message ID.
- `timestamp`: sender-side creation time for constructed messages; the server may
  construct a replacement envelope for broadcast.
- `data`: type-specific object.

Although `Protocol::VERSION` is `1`, the version is not transmitted in the
envelope and there is no capability negotiation.

## Transports

### TCP

```text
4-byte unsigned big-endian JSON length
N bytes compact UTF-8 JSON
```

The server accepts at most 16 MiB of JSON after the four-byte prefix, bounds the
socket read buffer, and disconnects immediately on a larger declared length. It
disconnects after three malformed JSON/envelope messages or more than 60
complete messages in one one-second connection window. The default TCP port is
9527.

### WebSocket

The browser uses one JSON object per text frame. The default port is 9528.
The server configures Qt's incoming frame and message limits to the same 16 MiB
V1 JSON maximum used by TCP.

### Outbound backpressure

TCP and WebSocket sessions allow at most 24 MiB of pending socket writes. If a
new response would cross the high-water mark, the server disconnects that slow
consumer instead of growing an unbounded queue or silently dropping the event.

### Heartbeat and reconnect

- heartbeat interval: 30 seconds;
- server/client timeout: 90 seconds;
- reconnect interval: 5 seconds;
- Web and Windows reconnect attempts are bounded by their client constants;
- reconnect reauthentication uses page/process-memory credentials rather than
  a device/refresh session; business `connected` recovery occurs only after
  successful authentication;
- the active room or direct conversation resumes with `afterSequence`; a
  rejected restore clears the in-memory Windows session and returns to login.

## Message Type Inventory

### Authentication and lifecycle

`LOGIN_REQ`, `LOGIN_RSP`, `REGISTER_REQ`, `REGISTER_RSP`, `LOGOUT`,
`FORCE_OFFLINE`, `HEARTBEAT`, `HEARTBEAT_ACK`.

### Room messages, presence, and history

`CHAT_MSG`, `SYSTEM_MSG`, `HISTORY_REQ`, `HISTORY_RSP`, `USER_LIST_REQ`,
`USER_LIST_RSP`, `USER_JOINED`, `USER_LEFT`, `USER_ONLINE`, `USER_OFFLINE`,
`MARK_ROOM_READ`.

### Room lifecycle and settings

`CREATE_ROOM_REQ`, `CREATE_ROOM_RSP`, `JOIN_ROOM_REQ`, `JOIN_ROOM_RSP`,
`LEAVE_ROOM`, `LEAVE_ROOM_RSP`, `ROOM_LIST_REQ`, `ROOM_LIST_RSP`,
`DELETE_ROOM_REQ`, `DELETE_ROOM_RSP`, `DELETE_ROOM_NOTIFY`, `RENAME_ROOM_REQ`,
`RENAME_ROOM_RSP`, `RENAME_ROOM_NOTIFY`, `ROOM_SEARCH_REQ`, `ROOM_SEARCH_RSP`,
`ROOM_SETTINGS_REQ`, `ROOM_SETTINGS_RSP`, `ROOM_SETTINGS_NOTIFY`,
`SET_ROOM_PASSWORD_REQ`, `SET_ROOM_PASSWORD_RSP`, `GET_ROOM_PASSWORD_REQ`,
`GET_ROOM_PASSWORD_RSP`.

### Administration and recall

`SET_ADMIN_REQ`, `SET_ADMIN_RSP`, `ADMIN_STATUS`, `KICK_USER_REQ`,
`KICK_USER_RSP`, `KICK_USER_NOTIFY`, `DELETE_MSGS_REQ`, `DELETE_MSGS_RSP`,
`DELETE_MSGS_NOTIFY`, `RECALL_REQ`, `RECALL_RSP`, `RECALL_NOTIFY`.

### Room files

`FILE_SEND`, `FILE_NOTIFY`, `FILE_DOWNLOAD_REQ`, `FILE_DOWNLOAD_RSP`,
`FILE_UPLOAD_START`, `FILE_UPLOAD_START_RSP`, `FILE_UPLOAD_CHUNK`,
`FILE_UPLOAD_CHUNK_RSP`, `FILE_UPLOAD_END`, `FILE_UPLOAD_END_RSP`, `FILE_UPLOAD_CANCEL`,
`FILE_DOWNLOAD_CHUNK_REQ`, `FILE_DOWNLOAD_CHUNK_RSP`, `FILE_COS_PROGRESS`,
`ROOM_FILES_REQ`, `ROOM_FILES_RSP`, `ROOM_FILES_DELETE_REQ`,
`ROOM_FILES_DELETE_RSP`, `ROOM_FILES_NOTIFY`.

### Profile and room avatars

`AVATAR_UPLOAD_REQ`, `AVATAR_UPLOAD_RSP`, `AVATAR_GET_REQ`, `AVATAR_GET_RSP`,
`AVATAR_UPDATE_NOTIFY`, `CHANGE_NICKNAME_REQ`, `CHANGE_NICKNAME_RSP`,
`NICKNAME_CHANGE_NOTIFY`, `CHANGE_UID_REQ`, `CHANGE_UID_RSP`,
`UID_CHANGE_NOTIFY`, `CHANGE_PASSWORD_REQ`, `CHANGE_PASSWORD_RSP`,
`ROOM_AVATAR_UPLOAD_REQ`, `ROOM_AVATAR_UPLOAD_RSP`, `ROOM_AVATAR_GET_REQ`,
`ROOM_AVATAR_GET_RSP`, `ROOM_AVATAR_UPDATE_NOTIFY`.

### Contacts and direct messages

`USER_SEARCH_REQ`, `USER_SEARCH_RSP`, `FRIEND_REQUEST_REQ`,
`FRIEND_REQUEST_RSP`, `FRIEND_REQUEST_NOTIFY`, `FRIEND_ACCEPT_REQ`,
`FRIEND_ACCEPT_RSP`, `FRIEND_ACCEPT_NOTIFY`, `FRIEND_REJECT_REQ`,
`FRIEND_REJECT_RSP`, `FRIEND_REMOVE_REQ`, `FRIEND_REMOVE_RSP`,
`FRIEND_REMOVE_NOTIFY`, `FRIEND_LIST_REQ`, `FRIEND_LIST_RSP`,
`FRIEND_PENDING_REQ`, `FRIEND_PENDING_RSP`, `FRIEND_CHAT_MSG`,
`FRIEND_CHAT_SEND_RSP`,
`FRIEND_HISTORY_REQ`, `FRIEND_HISTORY_RSP`, `FRIEND_FILE_SEND`,
`FRIEND_FILE_NOTIFY`, `FRIEND_ONLINE_NOTIFY`, `FRIEND_OFFLINE_NOTIFY`,
`FRIEND_FILE_UPLOAD_START`, `FRIEND_FILE_UPLOAD_START_RSP`, `MARK_FRIEND_READ`,
`FRIEND_RECALL_REQ`, `FRIEND_RECALL_RSP`, `FRIEND_RECALL_NOTIFY`.

The generated JSON inventory is the exhaustive change detector. This categorized
list explains ownership and is reviewed manually.

## Current Message Semantics

### Room passwords

`CREATE_ROOM_REQ` and `SET_ROOM_PASSWORD_REQ` accept an optional/non-empty
password subject to the common 4–1024 character bound. The server stores only an
Argon2id encoding; a successful join with a legacy plaintext row upgrades that
row. `GET_ROOM_PASSWORD_RSP` is an administrator-only status response containing
`success`, `roomId`, and `hasPassword`. It never contains the password value.
Empty `SET_ROOM_PASSWORD_REQ.password` clears protection.

### Room chat

Request data normally includes `roomId`, `content`, and `contentType`. Clients may
send `sender`, but persisted sender identity comes from the authenticated
session. Updated clients also send `clientMessageId` (1–128 UTF-8 bytes). For an
older client that omits it, the server uses the existing envelope `id`; retrying
that exact frame therefore retains one idempotency key. The server requires
durable room membership before persistence.

`CHAT_SEND_RSP` is the durable submission acknowledgement:

- `success` and `roomId` identify the result; a valid `clientMessageId` is
  echoed, while an invalid oversized identifier is not reflected;
- success adds stable server `id`, per-room `sequence`, authoritative
  `timestamp`, and `duplicate`;
- `duplicate: false` means this request created the durable message;
- `duplicate: true` returns a prior accepted result without another broadcast;
- failure adds stable `errorCode` and localized `error`;
- success means accepted by durable server storage, not delivered or read.

Committed `CHAT_MSG` adds optional `clientMessageId`, stable database `id`,
per-room `sequence`, authenticated `sender`, `senderName`, and a server
timestamp. Reusing a sender's `clientMessageId` for different room/content/type
fails with `CLIENT_MESSAGE_ID_CONFLICT`.

### Direct chat

Direct text/emoji submission follows the room reliability contract with a
sender-scoped `clientMessageId`, envelope-ID fallback, stable database `id`,
per-friendship `sequence`, authoritative timestamp, and explicit
`FRIEND_CHAT_SEND_RSP`. Exact retries recover the original result without a
second insert or broadcast; conflicting reuse returns
`CLIENT_MESSAGE_ID_CONFLICT`. Missing users and non-friends both return
`FRIENDSHIP_ACCESS_DENIED`. Success means durable acceptance, not recipient
delivery or read. Offline delivery is reconstructed from history and last-read
IDs.

### History

Legacy room and direct history accept a count and an optional `before`
timestamp. Both histories also support additive sequence-resume mode:

- request `afterSequence` using the last persisted cursor;
- response rows have a greater `syncSequence` in ascending order. For an
  unmodified row it equals immutable creation `sequence`; for a recalled row it
  equals the newer `mutationSequence`;
- room responses may also contain `events`. A `messagesDeleted` event shares
  the same cursor namespace and carries `sequence`/`syncSequence`, mode,
  actual selected IDs, `timestamp`/`cutoffMs`, deleted file IDs, count,
  operator, operation ID, and `eventTimestamp`;
- `mode: "sequence"`, `nextSequence`, `lastSequence`, and `hasMore` define the
  next bounded request;
- persist and resend `nextSequence`; do not infer a missing message from numeric
  gaps because administration may physically delete rows;
- a final/empty page advances to the durable high watermark so deletion gaps do
  not stall synchronization;
- reconcile a repeated stable `id` or `clientMessageId` as an authoritative
  state update in place instead of discarding it only because it was already
  rendered. Client-only cache/UI state may be retained during that merge.

Room history/member responses require current room membership, and direct
history requires current friendship participation. Counts are clamped to 100;
non-positive counts use 50. Negative room sequence cursors fail with
`INVALID_SEQUENCE_CURSOR`, as do negative direct sequence cursors.
The upgraded Windows client persists room and direct-conversation cursors in its
account-partitioned SQLite cache. The M2 Web client persists both cursor kinds
in IndexedDB. Both request bounded follow-up pages after reconnect login. Room
pages apply mixed message/event pages in cursor order; direct pages merge
authoritative message/recall state using `syncSequence`.

The M2 Web client persists room/direct snapshots and unresolved text/emoji sends
in an account-partitioned IndexedDB repository. The Windows client persists
bounded room/direct drafts and unresolved text/emoji messages in its SQLite
repository. Reconnect/page-reload retry reuses the original `clientMessageId`;
ACK, live echo, and history reconcile that optimistic record in place. A
rejected send becomes `failed` and requires explicit retry; a send without a
response remains `sending` and is retried only after authentication and an
authoritative membership/friendship refresh. Client
`sending`/`failed`/`accepted` values are local presentation state, not new V1
delivery guarantees. Windows attachment commands are not yet restartable.

### Read state

`MARK_ROOM_READ` updates a last-read database ID without publishing a receipt.
`MARK_FRIEND_READ` monotonically advances the reader's persisted message-ID
watermark. Upgraded servers publish `FRIEND_READ_NOTIFY` to the peer with
`friendshipId`, `readerUsername`, and `lastReadMessageId`; `FRIEND_LIST_RSP`
also includes `peerLastReadMessageId` for restart recovery. This is a private-
conversation read watermark, not a per-device delivered receipt or a general
room receipt.

### Recall

Recall is limited to 120 seconds for normal user recall. Administration has
separate deletion operations. The first accepted room/direct recall reserves a
new conversation sequence in the same transaction as the state change.
`RECALL_RSP`/`FRIEND_RECALL_RSP` and live notifications add
`mutationSequence`; the response also adds `duplicate`. An identical retry
returns the stored sequence without another notification. Attachment cleanup is
checked again idempotently so a retry can repair a crash between the durable
recall commit and file deletion.
Sequence history replays the recalled row with its immutable `sequence`, newer
`mutationSequence`, and `syncSequence`, including after process restart.

Failures use stable codes: room/resource mismatch is
`RECALL_ACCESS_DENIED`, an ownership/time-window rejection is
`RECALL_REJECTED`, and storage failure is `RECALL_PERSISTENCE_FAILED`. Direct
recall uses `FRIEND_RECALL_REJECTED` or
`FRIEND_RECALL_PERSISTENCE_FAILED` without revealing whether an unrelated
message ID exists.

Administrative deletion uses a required `clientOperationId` (with the envelope
ID as a compatibility fallback), one durable room sequence, and a canonical
command fingerprint. Exact retries return the original result with
`duplicate: true`; conflicting key reuse returns
`CLIENT_OPERATION_ID_CONFLICT`. Selected targets are limited to 100. The
`selected`, `all`, `before`, and `after` modes are replayed through room history
as `messagesDeleted` events, while existing `DELETE_MSGS_NOTIFY` remains for
online old clients. Non-admin requests return `ADMIN_DELETE_ACCESS_DENIED`.
Room/message and direct-message/peer relationships are resolved or checked by
the server; client resource fields do not select an unrelated notification
target.

### Content types

Observed content types include `text`, `emoji`, `image`, `file`, `video`, and
`system`. The allowed set is not encoded as a versioned wire enum.
Client message content is limited to 64 KiB of UTF-8 and empty or unknown-type
messages are not persisted.

### Files

Updated Web clients and Windows Qt composer flows use `FILE_UPLOAD_START` or
`FRIEND_FILE_UPLOAD_START` only as the authenticated control plane. A successful
response may add `httpUploadPath`; the client combines it with the
login-provided HTTP endpoint and short-lived file token, then sends one raw
binary `PUT` with exact
`Content-Length`. It sends the existing `FILE_UPLOAD_END` only after HTTP 204,
so final membership/friendship authorization, metadata persistence,
notification, and optional COS replication remain server-controlled. The
upload ID without its owner's token is not authorization. Partial HTTP bodies
are deleted on disconnect.

Upgraded clients add the same `clientMessageId` to upload start and
`FILE_UPLOAD_END`. The server echoes it during negotiation and replies to
finalization with `FILE_UPLOAD_END_RSP`. Successful response data contains
`success`, `uploadId`, `clientMessageId`, stable message `id`, signed `fileId`,
`sequence`, authoritative `timestamp`, and `duplicate`. An identical retry,
including one after restart, returns the original values and emits no second
notification. Reuse for a different command returns
`CLIENT_MESSAGE_ID_CONFLICT`. Clients that omit the field retain the old
at-least-once behavior and can ignore the additive response.

Updated Web and Windows clients download room/friend files from
`GET /api/download/{signedFileId}` with the login file token. The normal Windows
path streams to a temporary file and then imports it into the user cache. The
legacy `FILE_DOWNLOAD_REQ` Base64 response and WebSocket chunk messages remain
old-server fallbacks, not the preferred product data plane.

Upgraded Windows clients forward an existing attachment with
`FILE_FORWARD_REQ {sourceFileId, roomIds[], friendUsernames[]}` after login
advertises `serverFileForward: true`. The signed file ID follows the existing
convention: positive IDs identify room files and negative IDs identify friend
files. The server verifies current access to the source and each destination,
applies room quota, creates new file/message identities per successful target,
and returns `FILE_FORWARD_RSP` with per-target results and aggregate counts.
The request carries no file bytes. V1 bounds this synchronous compatibility
operation to an 8 MiB source and ten unique targets; V2 will use shared immutable
blob identity or asynchronous copying for larger files.

Every successfully persisted `FILE_NOTIFY` and `FRIEND_FILE_NOTIFY` now includes
the same positive per-conversation `sequence` and authoritative database
`timestamp` returned by sequence-based history. This applies to legacy inline
send, HTTP-upload finalization, and server-side forwarding. Older clients ignore
the additive fields. Upload-finalization acknowledgement and retry identity are
still separate remaining M1 work; the presence of a live sequence is not an
exactly-once delivery claim.

The legacy paths below remain for older Qt/Web versions and new-client fallback
against an older server during the compatibility window. Upgraded Web and
Windows normal paths no longer use them:

- files up to 8 MiB can use inline Base64 transfer;
- large source chunks default to 4 MiB and are Base64 encoded in JSON;
- the hard room-file ceiling is 10 GiB, subject to room settings;
- friend file ceiling is 100 MiB;
- downloads can use protocol chunks, server HTTP, or COS URLs depending on path
  and client behavior;
- every download path requires current room membership or friendship, and every
  large-upload mutation requires the authenticated upload owner.

Inline files must decode to the declared positive size, remain at or below
8 MiB, and use a basename-only filename of at most 255 UTF-8 bytes. Upload
chunks are limited to 4 MiB and the declared remaining size; upload completion
requires received bytes to exactly equal the declared total.

Passwords are limited to 1,024 characters before hashing. A connection may
submit at most five login, registration, or password-change commands per minute.
Valid authentication requests are additionally limited across connections by a
single-process gateway window, direct transport peer address, and normalized
account. A denial uses the request's existing response type with
`success: false` and `error`; it does not add a message type or required field.
Limiter state resets on server restart, and V1 does not trust a client-provided
proxy header as the peer identity.

Existing response types may include the additive fields `success: false` and
`error` when authorization or authentication-abuse checks fail. Existing
successful response shapes and message type names remain compatible.

## V1 Compatibility Rules

Until V1 retirement:

- do not rename message types or existing fields;
- add optional fields only when all existing readers safely ignore them;
- keep client-provided fields accepted when removal would break an old client,
  but ignore non-authoritative values on the server;
- create V2 for required-envelope, binary-encoding, identity, ordering, or
  semantic changes;
- test at least one previous client version against a changed server;
- update this document and the generated inventory in the same commit.

## Automated Compatibility Coverage

`Tests/v1_smoke_test.py` validates the current framed-TCP protocol against a real
server process. It checks that persisted sender identity comes from the
authenticated session, recipients see the same database message ID, history
contains the committed message, room membership survives reconnect, file
notifications retain metadata, and recall reaches another participant. It also
checks friend request/acceptance, accepted friend-list visibility, direct-message
fan-out and history, and direct-message recall.

`Tests/v1_authorization_test.py` uses three authenticated users to prove that
resource identifiers do not grant room, message, upload, or attachment access.
`Tests/v1_transport_limits_test.py` covers malformed/oversized input, unknown
types, message floods, the legacy 8 MiB inline-file boundary, and a real slow
consumer. `Tests/v1_input_validation_test.py` covers authentication work and
field/file/upload invariants. `python3 tools/verify_m0.py --v1-smoke` runs all
configured suites against the same built server binary in separate isolated
databases.

`Tests/v1_room_message_reliability_test.py` proves first acceptance, exact retry,
conflicting key reuse, old-envelope compatibility, non-member rejection,
sequence pagination, process-restart idempotency, interrupted-migration recovery,
deleted-high-watermark monotonicity, and structured accepted/duplicate/rejected
monitoring.

The test uses randomized users, payload tokens, temporary SQLite/files, and a
locally available three-port range. It must not depend on production credentials,
ports, files, or external COS access.

## Gaps to Address Before V2 Cutover

- transmitted protocol version and capability negotiation;
- device/session identity independent of passwords;
- delivered and read acknowledgement semantics;
- replayable sequence/cursor behavior for remaining non-message events;
- structured error code separate from localized message;
- retire legacy inline attachment fallbacks after the compatibility window;
- generated Java/C++/TypeScript schemas.
