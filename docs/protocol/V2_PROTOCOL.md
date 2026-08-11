# V2 Protocol

## Status

The V2 protocol is under additive M3 development. No supported client or
production route depends on it yet; V1 remains authoritative.

## Source of truth

The schema source is
`Backend/protocol-v2/src/main/proto/chat/v2/envelope.proto`. Generated files are
never edited by hand. Java/C++ verification output is ephemeral; the Web
TypeScript output is committed as reviewed application source and checked for
staleness by regeneration. The Gradle binding task uses the same pinned compiler
to emit Java, C++, TypeScript, and a descriptor; the TypeScript generator/runtime
are pinned by their own npm lockfile.

## Envelope v2

Every binary WebSocket message will carry one Protobuf `Envelope`:

| Field | Number | Meaning |
| --- | ---: | --- |
| `protocol_version` | 1 | Must be exactly `2` for this generation |
| `kind` | 2 | Command, response, event, or structured error |
| `message_type` | 3 | Positive numeric entry in the separately versioned payload registry |
| `request_id` | 4 | Correlates command/response/error; optional for unsolicited events |
| `session_id` | 5 | Server-issued session/device context; never trusted without authentication |
| `client_message_id` | 6 | Stable idempotency identity when the operation defines one |
| `sent_at_epoch_ms` | 7 | Sender diagnostic time; never authoritative for ordering or access |
| `payload` | 8 | Serialized registered payload, limited to 1 MiB at this boundary |

Identifiers are limited to 128 UTF-8 bytes. The gateway rejects version zero,
unknown envelope kinds, zero message types, non-positive sent time, missing
request IDs where required, and oversized identifiers/payloads before invoking
the application core. Feature payloads retain their own smaller limits.

The implemented gateway frame boundary aggregates fragmented binary messages
up to 1 MiB plus 1024 bytes of bounded envelope overhead. It rejects text,
stray continuation, oversized, malformed-Protobuf, and policy-invalid input
before dispatch. Ping, pong, and close frames are left to the WebSocket control
handler. Each outbound envelope is encoded as one final binary WebSocket
message. Malformed/invalid input closes with fixed status 1002 and reason
`invalid V2 frame`; oversized complete or fragmented input closes with status
1009 and reason `V2 frame too large`. Parser or exception details are never
reflected. The pre-cutover listener is runnable, but no supported client sends
product traffic to V2.

## Control message registry

Registry values are permanent and a removed value must be reserved rather than
reused:

| `message_type` | Payload | Required envelope kind | Direction/state |
| ---: | --- | --- | --- |
| 1 | `ClientHello` | command | client to server, first application frame |
| 2 | `ServerHello` | response | server to client after successful negotiation |
| 3 | `ProtocolError` | error | server to client for a bounded safe protocol failure |
| 10 | `Authenticate` | command | negotiated client to server; fresh credentials |
| 11 | `ResumeSession` | command | negotiated client to server; opaque resume proof |
| 12 | `SessionEstablished` | response | server to client; authenticated connection context |
| 13 | `AuthenticationRejected` | error | server to client; generic rejection or rate limit |
| 100 | `SubmitMessage` | command | authenticated client to server; durable idempotent append |
| 101 | `MessageAccepted` | response | server to submitting client after durable commit |
| 102 | `ReadMessageHistory` | command | authenticated client to server; forward sequence page |
| 103 | `MessageHistoryPage` | response | server to requesting active member |
| 110 | `ListConversations` | command | authenticated client to server; bounded directory page |
| 111 | `ConversationDirectoryPage` | response | server to authenticated active member |

`SubmitMessage` carries a canonical conversation UUID and a registered content
type. Content type 1 is permanently assigned to nonempty valid UTF-8 text with a
65,536-byte maximum; other values are currently rejected. The outer content
budget remains 1,000,000 bytes for future registered schemas. The envelope
`client_message_id` is required
and is the sender-scoped idempotency key; envelope/session/payload identity never
overrides the server-bound authenticated account/device. `MessageAccepted`
returns the stable server UUID, positive conversation sequence,
PostgreSQL-authoritative acceptance time, and duplicate flag. Durable acceptance
does not mean destination delivery or read acknowledgement.

`ReadMessageHistory` accepts a nonnegative signed-server-range `after_sequence`
and limit 1..100. `MessageHistoryPage` returns at most 100 ascending records, an
explicit next cursor, current conversation high watermark, and `has_more`.
Deleted rows may leave sequence gaps. Authorization failures use the opaque
`NOT_AUTHORIZED` protocol code; conflicting reuse of a client message ID uses
`IDEMPOTENCY_CONFLICT`.

After authentication, the gateway dispatches types 100 and 102 through the
transport-independent application ports and PostgreSQL adapter. It uses only
the server-bound account/device identity, executes database work outside the
Netty event loop, and serializes at most one command per connection with 16
additional queued commands. Saturation returns retryable `RATE_LIMITED`; an
unexpected application failure returns retryable `INTERNAL_ERROR`. Neither
expected business denial nor retryable failure closes the authenticated
connection. This is an additive pre-cutover path: no supported client sends V2
product traffic yet, and delivery/read/fan-out are not implemented by these four
types.

`ListConversations` uses a limit of 1..100 and either no cursor or the complete
pair `(after_updated_at_epoch_ms, after_conversation_id)`. Directory records are
ordered by that server-owned pair descending and expose canonical kind, bounded
display name, caller role, latest sequence, caller read sequence, and update
time. A nonempty page repeats its last row as the next cursor; an empty page has
no cursor. This cursor supports bounded directory browsing only. Missing-message
recovery still uses each conversation's contiguous sequence, never directory
timestamps. The authenticated gateway dispatches type 110 through the same
connection-local serial queue and isolated worker pool as message submission and
history, using only the server-bound account identity. No supported client sends
this pre-cutover command yet.

The additive Web protocol client consumes the generated TypeScript schemas but
does not yet own a WebSocket or replace the live V1 connection. Its fail-closed
state machine emits bounded binary commands for hello, fresh authentication,
directory listing, history reads, and idempotent UTF-8 text submission. It
accepts only correlated response/error envelopes of the registered kind and
type, preserves the validated `request_id` and `client_message_id` on decoded
application events, validates the authenticated session and feature payload invariants, caps
pending requests at 16, and retains the one-time resume proof only in memory.
Password input is copied only for immediate serialization and that owned copy is
cleared. Connection/reconnect ownership and durable resume-token storage remain
explicitly deferred; therefore this is not a V2 traffic cutover.

The adjacent WebSocket lifecycle adapter requests exactly `wss://<authority>/v2/web`
with `chat.v2`, verifies the server-selected subprotocol, requests ArrayBuffer
delivery, and lets the protocol state machine reject malformed or semantically
invalid binary data. Positive connect, hello, and authentication deadlines close
stalled sockets. Unexpected closure clears all per-connection protocol/session
state and schedules capped exponential full-jitter reconnect; successful session
establishment resets the attempt counter. Stop cancels phase and reconnect timers.
The adapter does not silently replay credentials or authenticated commands after
reconnect. Once negotiated, callers may explicitly supply a session UUID and
32-byte proof; the protocol boundary copies and clears its serialization buffer,
then validates and retains only the rotated proof returned by the server.
Persistent proof custody, automatic resume orchestration, a browser-appropriate
liveness mechanism, online/offline integration, and UI rollout remain later
slices.

The pre-cutover Web application coordinator consumes those validated events. It
uses the envelope `client_message_id` to reconcile optimistic sends and treats a
later history copy as the same message. A durable acceptance response updates
presentation and server identity but deliberately does not advance the
conversation synchronization cursor; only a validated history page can advance
the last contiguous sequence. This prevents a locally accepted high sequence
from skipping unseen messages after reconnect.

`ClientHello` declares a minimum/maximum protocol generation, Web or Windows
platform, app version, and client-device ID. App version is limited to 64 UTF-8
bytes and device ID to 128 UTF-8 bytes. It intentionally contains no credential
or resumable session secret. A structurally valid range that does not include V2
is an unsupported-version result; it is not treated as malformed input.

`ServerHello.connection_id` is diagnostic connection identity, not an
authenticated session.

The implemented pre-auth state machine requires ClientHello as the first
application frame, limits its serialized payload to 512 bytes, and permits only
one successful negotiation. It returns fixed safe protocol errors and closes on
wrong first frame, invalid payload, unsupported version, or repeated hello.
After success, the negotiated client descriptor is retained as untrusted
server-side channel state; `ServerHello` alone grants no identity or
permissions.

Before WebSocket upgrade, the gateway reserves `/v2/web` for a
configured HTTPS Origin allowlist and `/v2/windows` for native requests without
Origin. Both require one exact configured TLS Host authority and the single
WebSocket subprotocol `chat.v2`; unversioned or multi-valued subprotocol offers
are rejected. It freezes the expected platform in server-owned channel state. A later
`ClientHello.platform` must match that endpoint or the gateway returns a fixed
invalid-payload error and closes. Paths, headers, and platform claims do not
authenticate an account.

`Authenticate.password_utf8` is limited to 1..1024 valid UTF-8 bytes and must
never be logged, persisted, cached, or echoed. `ResumeSession.resume_token` is
exactly 32 opaque bytes; only its SHA-256 digest may be stored. A successful
session response returns a newly issued raw token once and binds identity in
server-side connection state. Unknown account, wrong password, disabled account,
invalid token, and expired/revoked session all use the same generic rejection to
avoid enumeration.

The fresh-login gateway state machine accepts one `Authenticate` command,
dispatches password verification through an injected non-event-loop executor,
and binds the issued account/device/session UUIDs to server-side channel state.
It returns the raw resume token once and clears its owned bytes. Later envelopes
must carry the same session ID, but downstream authorization uses the bound
server identity rather than trusting that field. Session resume uses the same
bounded worker and connection state, verifies and rotates the proof atomically
in PostgreSQL, and returns a newly rotated token. Invalid UUID/proof, unknown,
wrong, expired, revoked, device-mismatched, and replayed sessions share the
generic rejection response.

Authentication execution uses a fixed worker count and bounded queue owned by
the gateway lifecycle. Queue saturation clears the unadmitted password command,
does not invoke Argon2id or persistence, and returns the generic
`AuthenticationRejected` payload with reason `RATE_LIMITED` and a fixed
one-second retry hint. This protects executor capacity alongside the implemented
process-local account, direct-peer-IP, and gateway-window abuse limits.

Once installed on a channel, independent positive deadlines bound the time to
send a valid `ClientHello` and the time from negotiation to server-side identity
binding. Expiry closes with WebSocket status 1008 and the fixed reason
`V2 handshake timeout` or `V2 authentication timeout`. Successful phase changes
and disconnect cancel their scheduled tasks. Deployment durations remain
explicit configuration and are not defined by the short virtual-time tests.

After server-side identity binding, a reader-idle event closes the WebSocket with
status 1001 and the fixed reason `V2 idle timeout`. Pre-authentication idle time
is governed by the stricter handshake/authentication deadlines. The listener
places its reader-idle timer before WebSocket control handling so
valid ping/pong traffic refreshes connection activity.

Before password copying or worker submission, the single-process gateway
applies cumulative fixed-window limits to total attempts, the resolved direct
socket peer, and a normalized account key. Key maps are bounded and fail closed
at capacity. A verified login clears only its account bucket. Denials use the
same generic `RATE_LIMITED` payload and expose neither limiting key. Direct mode
ignores forwarded headers. The installed proxy boundary
accepts them only from configured numeric CIDRs, uses bounded right-to-left
chain resolution, freezes the canonical peer before upgrade, and fails closed
on trusted-proxy errors. Multi-gateway coordination remains an M5 Redis concern.

Authentication telemetry uses only fixed outcome/limiter labels. It counts
accepted, rejected, failed, saturated, and credential-upgrade-pending outcomes
and records fixed execution-duration buckets. Saturation and admission warnings
are sampled at power-of-two totals and contain only event, dimension, and count.
Account, peer, request, exception, password, and token values are excluded.

These messages are not allowed on a production route. `GatewayMain` now composes
the WSS component with PostgreSQL, identity cryptography, bounded workers, admin
readiness/metrics, ordered shutdown, Host/Origin/proxy controls, upstream idle
timer, and bounded transport parsing. Durable conversation/message dispatch,
cutover/rollback rehearsal, and deployment capacity evidence remain unfinished.

## Compatibility rules

- Field numbers are permanent. Removed fields are reserved rather than reused.
- New fields must be additive and old readers must preserve unknown fields when
  relaying an envelope.
- `message_type` values are never silently reinterpreted. Their registry and
  typed payload schemas are added in subsequent vertical slices.
- Server sequence and timestamp remain authoritative; `sent_at_epoch_ms` is not
  a message ordering key.
- V1 JSON translation stays at the gateway boundary and must not leak V1 row or
  field quirks into V2 application types.

The Java and TypeScript bindings must encode and decode the stored golden
envelope, ClientHello, and non-secret Authenticate fixture identically. The
generated C++ binding is compiled against the matching
SHA-256-pinned Protobuf/Abseil test runtime and must parse and re-emit those same
bytes. This completes envelope-level cross-language evidence; feature payload
registries still require their own additive compatibility tests.
