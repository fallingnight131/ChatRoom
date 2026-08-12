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

The Windows client has an optional TLS socket mode for a future trusted
deployment endpoint. That mode requires the system peer chain and exact host
name and does not expose application connectivity until the encrypted handshake
finishes. It has no certificate-ignore option. The current Qt V1 server listener
is plaintext; direct plaintext use is a loopback development path, not public
transport security evidence.

### WebSocket

The browser uses one JSON object per text frame. The default port is 9528.
The server configures Qt's incoming frame and message limits to the same 16 MiB
V1 JSON maximum used by TCP.

### Same-origin HTTP health

`GET /api/health` is the query-free, unauthenticated routing check used by the
Web release gate. It returns exactly `{"protocol":"v1","status":"ok"}` plus a
newline as `application/json; charset=utf-8`, with `Cache-Control: no-store` and
`X-Content-Type-Options: nosniff`. It exposes no database, user, file, build, or
dependency state and therefore proves only that the V1 HTTP process and route
are reachable. Path variants, query strings, and non-GET requests are not
healthy responses. Older servers do not implement this additive endpoint.

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

The inactive Java compatibility codec and Netty handler currently cover
`LOGIN_REQ`, `LOGIN_RSP`, client-driven `HEARTBEAT`/`HEARTBEAT_ACK`, and the
server-emitted `FORCE_OFFLINE` replacement event; they are not connected to a
listener. The login codec
accepts the established envelope and credential fields, caps this small command
at 16 KiB, rejects
duplicates, trailing JSON, nesting/string-limit violations, missing fields,
usernames over 20 UTF-16 code units, and passwords over 1024 UTF-16 code units.
Success encodes only the V1 numeric `userId`, username, and display name. Failure
uses one generic credential error. File authorization fields and all other V1
message types remain on the C++ server until their own vertical slices exist.
The handler permits one attempt per connection, runs credential work on the
bounded authentication executor, applies the shared admission limits, and binds
the numeric V1 identity plus canonical UUID identity only in server-side channel
state. Every failure class uses the same response and closes the connection.
Successful login atomically owns the process-local V1 account connection. A
newer login for that account sends the established fixed-reason `FORCE_OFFLINE`
envelope to the displaced connection and closes it; close cleanup is conditional
so an older connection cannot unregister its replacement. The event exposes no
canonical UUID, session identifier, or resume proof.
The composed inactive pipeline responds to a valid authenticated `HEARTBEAT`
with a bounded empty-data `HEARTBEAT_ACK`, consumes redundant acknowledgements,
and leaves all business frames for later compatibility handlers. The configured
reader-idle deadline closes authenticated connections; it does not reinterpret
malformed or unknown business input as lifecycle traffic.
The inactive Java pipeline also requires login within its configured
post-upgrade deadline. Expiry uses WebSocket policy close reason
`V1 authentication timeout`; successful server-side identity binding cancels
the deadline before later application traffic is accepted.
Its future browser transport is reserved at exact WSS path `/v1/web`, requires
the single WebSocket subprotocol `chat.v1`, one configured HTTPS Origin, and no
query string. It is distinct from `/v2/web` + `chat.v2`; the guard is tested but
remains outside all active listeners until routing and the required post-login
commands are complete.
After a future HTTP dispatcher selects that route, the detached upgrade adapter
still requires the server-side guard marker plus the exact handshake path and
negotiated subprotocol before it installs login, timeout, replacement, and
heartbeat handlers. Upgrade mismatch is a fixed policy close.

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

The detached Java compatibility application can now derive the existing
`ROOM_LIST_RSP.data.rooms` projection from PostgreSQL: imported numeric
`roomId`, canonical `roomName`, sequence-derived `unread`, and `isAdmin` for
canonical owner/admin roles. It scans at most 1,000 authorized directory rows,
uses bounded batch mapping, preserves ascending numeric room order, and fails
instead of emitting a partial list when imported mappings disagree. A detached
strict codec and handler
now accept one authenticated request at a time, execute it off the event loop,
cap the encoded response at 1 MiB, and close with a generic reason without a
response on malformed owned input, saturation, or dependency/mapping failure.
The detached compatibility module composes login, heartbeat, and this handler;
the disposable PostgreSQL gate verifies authorized imported room output and
unrelated-room exclusion, but `GatewayRuntime` still installs no V1 product
listener.

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

The detached Java application boundary now reserves `ROOM_SEARCH_REQ` as an
authenticated bounded read. It accepts a trimmed non-empty control-free keyword
of at most 256 UTF-8 bytes and returns at most 20 UUID-free mapped rooms with
`roomId`, `roomName`, `creatorId`, and active `memberCount`. A future PostgreSQL
adapter will preserve positive decimal exact-ID lookup and otherwise apply
literal case-insensitive title matching. The repeatable-read PostgreSQL adapter
now enforces those lookup modes, requires
an enabled mapped actor, counts active members, and rejects incomplete active-
OWNER mappings instead of returning a partial list. The detached strict handler
binds authenticated identity, executes off-loop,
returns compatible `ROOM_SEARCH_RSP`, and emits only fixed result telemetry.
Malformed or infrastructure-failed work closes; policy-rejected input returns
an unsuccessful response without closing. The product listener remains inactive.

The detached Java application boundary now also reserves `CREATE_ROOM_REQ`.
Authenticated state owns the creator; the bounded envelope ID is future
idempotency identity. The title is trimmed and bounded to 100 Unicode code
points. An optional valid UTF-8 password retains the V1 4–1024 character policy,
is copied into owned secret memory, hashed before persistence, and zeroed on
every exit. Protected-room retry comparison uses a dedicated server-keyed,
domain-separated stable tag beside the salted slow hash, never an unkeyed fast
password digest. The atomic result returns positive `roomId`, normalized
`roomName`, `isAdmin: true`, and duplicate state without exposing UUIDs. V023 and the serializable
PostgreSQL adapter now persist GROUP, OWNER, optional Argon2id credential, ROOM
mapping, and keyed idempotency record atomically; exact retries recover the same
numeric room ID while conflicting title or password-tag reuse is rejected. The crypto adapter now emits compatible
salted Argon2id plus `hmac-sha256:v1` tags under an owned, close-zeroed 32-byte
runtime key. The detached runtime parser now requires explicit canonical padded
Base64 for exactly 32 bytes and provides no default. The detached strict handler
now binds authenticated creator
and outer envelope ID, accepts only `roomName` plus optional `password`, clears
all password copies, and returns UUID-free `CREATE_ROOM_RSP`. Success includes
`isAdmin: true` and optional-compatible `duplicate`; failures include stable
`errorCode`. Exact retry recovers the same room ID and replacement login
recovers the room through `ROOM_LIST_RSP`. The product listener remains inactive.

The detached Java application boundary now also reserves `JOIN_ROOM_REQ`.
Authenticated state owns the joining account and only a positive mapped
`roomId` plus optional owned UTF-8 password bytes form the command. Existing
active membership succeeds idempotently without another password challenge. A
first join distinguishes missing and invalid credentials, verifies the exact
stored credential through the shared crypto port, and passes that access
snapshot into the atomic PostgreSQL mutation. V024 stores a bounded GROUP
member limit. The adapter locks that policy and rechecks enabled mapped account,
GROUP/ROOM target, credential snapshot, active membership, and capacity before
inserting or reactivating one member. Concurrent last-place attempts admit only
one account. Success will preserve `roomId`, `roomName`, `isAdmin`, and
`newJoin`; UUIDs and password hashes remain internal. Custom V1
`room_settings.max_members` is now required in the physically verified SQLite
schema, bound into the import fingerprint, validated from 1 through 1000000,
and exactly reconciled into the GROUP policy. A V024 default may change only by
counted compare-and-set from 50; any other mismatch blocks import. The detached
strict handler now accepts only `roomId`
plus optional password, applies process/peer/room admission before password
verification, and executes off-loop. `JOIN_ROOM_RSP` preserves `needPassword`,
`isAdmin`, and `newJoin`, adds stable `errorCode` and optional `retryAfterMs`,
and never exposes UUIDs or hashes. Only a committed `newJoin:true` emits one
process-local `USER_JOINED`; exact repeat and every rejection emit none. Real
PostgreSQL proves missing/wrong/correct password behavior, one membership, one
notification, duplicate suppression, and replacement-login room recovery. The
product listener remains inactive.

### Room chat

Request data normally includes `roomId`, `content`, and `contentType`. Clients may
send `sender`, but persisted sender identity comes from the authenticated
session. Updated clients also send `clientMessageId` (1–128 UTF-8 bytes). For an
older client that omits it, the server uses the existing envelope `id`; retrying
that exact frame therefore retains one idempotency key. The server requires
durable room membership before persistence.

The detached Java application boundary reserves the same room text/emoji
contract. Authenticated state owns sender account/device identity; only the V1
room ID, stable client ID, content, and presentation type are accepted as
intent. The PostgreSQL adapter now creates canonical and V1 ROOM message
identity atomically after active membership/device checks. Exact retry preserves
the durable result and suppresses a second broadcast. The detached strict
handler returns the mapped ACK, echoes the authoritative first acceptance to
the sender, then fans it out only to currently connected accounts that a batch
PostgreSQL query confirms are active mapped members. Exact retry emits only the
duplicate ACK. This is process-local best-effort notification rather than a
delivery guarantee; reconnect recovery remains history based and the product
listener remains inactive.

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

The detached Java boundary now reserves the same direct text/emoji contract.
Authenticated state owns sender account/device identity and the exact target
username is resolved server-side. PostgreSQL must atomically create the
canonical message plus its positive 32-bit V1 message mapping before the
compatible ACK can succeed. Exact retry must return the same friendship/message
IDs, sequence, and timestamp with `duplicate=true`; only first acceptance may
later emit `FRIEND_CHAT_MSG`. The PostgreSQL adapter now performs the canonical
message and V1 mapping write atomically after active relationship/device checks;
the detached handler now returns that mapped ACK and emits `FRIEND_CHAT_MSG` to
the sender and current local target only on first acceptance. Exact retry emits
no live message. Malformed or infrastructure-failed handling closes and the
product listener remains inactive.

The detached Java boundary now also reserves `MARK_FRIEND_READ`. It binds the
reader from authentication, accepts only a positive mapped friendship ID, and
requires monotonic canonical sequence advancement. `lastReadMessageId` is the
mapped V1 ID of the newest message by creation sequence at or below that cursor;
it is never `MAX(id)` because runtime V1 message IDs allocate downward. The
result carries the mapped peer for compatible `FRIEND_READ_NOTIFY` and
directory recovery. The serializable PostgreSQL adapter now locks the exact
active participant and DIRECT conversation, advances only that account to the
observed durable high watermark, and selects the mapped message by canonical
creation sequence. `FRIEND_LIST_RSP.peerLastReadMessageId` uses the same
sequence ordering, including when runtime IDs descend. The detached strict
handler preserves the response-free request shape, binds the reader from
authenticated state, and publishes `FRIEND_READ_NOTIFY` only to
the server-mapped current local peer after persistence succeeds. Exact repeats
may republish the same monotonic watermark. A replacement login recovers that
watermark from `FRIEND_LIST_RSP`; the product listener remains inactive.

The detached Java application boundary now also reserves owner-only direct
recall. It accepts only the positive V1 `messageId` from the request and binds
the actor from authentication; client peer/sequence/time fields are not
authority. First apply retains the existing 120-second policy and allocates an
atomic canonical mutation sequence. The PostgreSQL adapter enforces these
rules with database time and retry-convergent serialization. Exact owner retry
returns the same event with `duplicate: true` and no second notification. The
detached handler returns mapped peer/mutation fields and schedules a local peer
notification only on first apply. Real replacement-login verification proves
the next history page reconciles that recall by `mutationSequence`; the product
listener remains unchanged.

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

The detached Java room-history boundary reserves this mixed reconnect model.
It binds the reader to authenticated account state, accepts a positive mapped
room ID, and limits each page to 100 combined message/event items. Text/emoji
messages fold recall state through `mutationSequence`; deletion events retain
their shared sequence and bounded positive V1 message/file identity lists.
Latest timestamp pages contain messages only, preserving the existing response
shape. The PostgreSQL adapter now reads one repeatable snapshot, verifies the
complete mapped room state before paging, merges message/recall/deletion cursor
items before enforcing the combined bound, and advances final pages across
physical-deletion gaps. The detached strict handler now returns bounded
`HISTORY_RSP` pages and closes generically for malformed, saturated, or failed
reads. Real replacement-login verification proves a durable room message is
recovered after its prior cursor; the product listener remains inactive.

The detached Java direct-history boundary preserves both direct modes. Its
PostgreSQL adapter now reads one repeatable snapshot and resolves the exact peer
and active friendship from authenticated state, emits
only mapped text/emoji messages, and folds a recall entry into the original row
with `mutationSequence` and `recalled=true`. Sequence pages are strictly ordered
by `syncSequence`, bounded to 100, and fail closed on missing V1 IDs or partial
state. The detached Java handler normalizes the existing count policy, rejects
malformed inputs safely, and emits legacy-only message identity plus compatible
cursor metadata. A real second login recovers a message after its stored cursor;
the product listener remains unchanged.

Canonical PostgreSQL stores both V1 text and emoji as registered UTF-8 message
type 1. V020 preserves the original `contentType` only in the V1 message mapping
so history can reproduce presentation exactly; missing pre-cutover metadata must
be repaired by verified import and is not guessed.

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

The detached Java compatibility module now reproduces `FRIEND_LIST_RSP` from
imported PostgreSQL state, including numeric friendship/friend IDs, online,
message-row unread count, `peerLastReadMessageId`, and pending request count.

The detached Java boundary now reserves `MARK_ROOM_READ` as an authenticated,
positive mapped room command. It advances canonical `last_read_sequence`
monotonically to a transactionally observed durable high watermark; it does not
interpret descending runtime V1 message IDs as order and emits no room receipt.
The serializable PostgreSQL adapter now locks the exact active member and room,
advances only that account to the observed durable high watermark, and returns
unchanged on repeat. The detached strict handler preserves the response-free V1
shape, closes malformed or unavailable work, and records fixed outcome/delta
telemetry. Real PostgreSQL verifies the next room list reports zero unread; the
product listener remains inactive.
Any incomplete compatibility projection closes the detached connection rather
than sending an empty authoritative list. This route remains inactive.

The same detached module reproduces `FRIEND_PENDING_RSP` with mapped numeric
request/requester IDs, requester names, and canonical creation epoch
milliseconds. Incomplete mapping or dependency failure closes without a partial
action list; this route also remains inactive.

The detached module also parses `FRIEND_REJECT_REQ.data.requestId` as one
positive 32-bit-compatible integer and takes the recipient only from the
authenticated connection. First rejection and an exact same-recipient retry
both return `FRIEND_REJECT_RSP.data.success=true`; ordinary authorization or
state denial returns `success=false` with the existing generic
`error="\u5904\u7406\u597d\u53cb\u8bf7\u6c42\u5931\u8d25"`. No new duplicate field or canonical UUID is
added. Malformed, saturated, or dependency-failed handling closes rather than
inventing a mutation result. This composed route remains detached from the
product listener.

The detached user-search boundary reserves the existing `USER_SEARCH_REQ` shape:
`data.keyword` is trimmed, non-empty, free of control characters, and limited to
256 UTF-8 bytes. It will return at most 20 compatible `users` with only
`userId`, `username`, `displayName`, and rebuildable `online`; the authenticated
caller is excluded and no UUID is exposed. `%` and `_` are literal search text,
not client-controlled SQL wildcards.

The detached module now composes that search. A valid policy-rejected keyword
returns the existing `USER_SEARCH_RSP.data.success=false` empty-keyword error;
successful `users` contain exactly the four fields above. Malformed requests,
saturation, and dependency failure close instead of fabricating an empty success
list. Presence is process-local and may change across requests. The product
listener remains inactive.

The next detached contact boundary defines `FRIEND_REQUEST_REQ.data.username`
as one exact non-control V1 username of at most 128 UTF-8 bytes. The authenticated
connection owns the requester and persistence resolves the recipient. A first
same-direction request and its exact pending retry both return `success=true`,
but only first apply may emit `FRIEND_REQUEST_NOTIFY`. Missing user, self,
already-friend, reverse-pending, and invalid target retain distinct failure
semantics.

That detached handler is now composed. First creation sends the compatible
success response and schedules one `FRIEND_REQUEST_NOTIFY` containing the
authenticated requester's username/display name when the durable recipient has
a current local V1 connection. Exact retry succeeds without another notification.
Malformed or infrastructure-failed handling closes. Product routing remains inactive.

The next detached compatibility boundary defines `FRIEND_ACCEPT_REQ` with the
same positive `data.requestId` and authenticated-recipient rule. First apply and
an exact retry will both preserve `FRIEND_ACCEPT_RSP.data.success=true`, but only
the first apply may produce `FRIEND_ACCEPT_NOTIFY`; a retry must not duplicate
the notification. Failure remains the existing generic response and no UUID or
new duplicate marker is added.

That detached handler is now composed. `data.fromUsername` remains an optional
bounded string because existing Windows and Web clients send it, but the server
ignores it for authorization and notification routing. First acceptance sends
`FRIEND_ACCEPT_RSP.data.success=true` and, only when the durable requester has a
current local V1 connection, one `FRIEND_ACCEPT_NOTIFY` containing authoritative
`acceptedBy` and `acceptedByDisplay`. An exact retry returns success without a
second notification. Ordinary denial returns the legacy generic error; malformed
or infrastructure-failed handling closes. The product listener remains inactive.

The next detached contact boundary defines `FRIEND_REMOVE_REQ.data.username` as
one exact non-control V1 username of at most 128 UTF-8 bytes. The authenticated
connection owns the actor and persistence resolves the target. First removal
and an exact response-loss retry will both preserve
`FRIEND_REMOVE_RSP.data.success=true` plus the exact target `username`, but only
the first may emit `FRIEND_REMOVE_NOTIFY`. Removal ends both active canonical
DIRECT memberships and retains the conversation, V1 mapping, messages, entries,
and read cursors. Self removal retains its specific legacy error; other denials
retain the generic failure. The PostgreSQL adapter now implements this atomic
decision and fails closed on partial membership state. The detached handler now
composes compatible responses and authoritative first-only notification;
malformed or infrastructure-failed handling closes. The product listener remains
inactive.

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

The detached Java boundary now also reserves room recall. It accepts only the
positive V1 `roomId` and `messageId`, binds the actor from authentication, and
requires the future PostgreSQL transaction to resolve both mappings, active
membership, ownership, database-time window, and one canonical mutation
sequence together. Exact retry must return the same mapped result with
`duplicate: true` and no second notification. The serializable PostgreSQL
adapter now enforces those checks, converges concurrent first/retry races, and
permits only the durable original actor to recover an exact result after the
window or membership removal. The detached strict handler now returns mapped
`RECALL_RSP`, echoes first apply to the sender, and notifies only connected
accounts confirmed by a batch active-membership query. Exact retry emits no
notification. Real replacement-login verification proves room history folds the
new mutation sequence; the product listener remains inactive.

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

- the Java compatibility path may authenticate only accounts carrying an exact
  V1 numeric-ID mapping; V2-native accounts are rejected before session issue;
- Java V1 login results retain server-bound UUID account/device/session identity
  internally but expose only the established V1 numeric `userId`, username, and
  display name to the future JSON encoder; V2 resume secrets never enter V1 JSON;

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
configured suites, including the exact unauthenticated `/api/health` contract,
against the same built server binary in separate isolated databases.

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
- delivered acknowledgement plus room and per-device read semantics (private
  conversation-level read watermarks are defined by ADR-0030);
- replayable sequence/cursor behavior for remaining non-message events;
- structured error code separate from localized message;
- retire legacy inline attachment fallbacks after the compatibility window;
- generated Java/C++/TypeScript schemas.
