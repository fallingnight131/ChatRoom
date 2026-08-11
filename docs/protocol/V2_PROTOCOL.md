# V2 Protocol

## Status

The V2 protocol is under additive M3 development. No supported client or
production route depends on it yet; V1 remains authoritative.

## Source of truth

The schema source is
`Backend/protocol-v2/src/main/proto/chat/v2/envelope.proto`. Generated files are
build output and are not edited or committed by hand. The Gradle binding task
uses the same pinned compiler to emit Java, C++, TypeScript, and a descriptor;
the TypeScript generator/runtime are pinned by their own npm lockfile.

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
reflected. The listener, timeouts, rate limits, and production transport policy
are not enabled yet, so V2 still has no production route.

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
server identity rather than trusting that field. Unsupported session resume and
credential failures share the generic rejection response.

Authentication execution uses a fixed worker count and bounded queue owned by
the gateway lifecycle. Queue saturation clears the unadmitted password command,
does not invoke Argon2id or persistence, and returns the generic
`AuthenticationRejected` payload with reason `RATE_LIMITED` and a fixed
one-second retry hint. This protects executor capacity; it is not a substitute
for the pending account, direct-peer-IP, and gateway-window abuse limits.

Once installed on a channel, independent positive deadlines bound the time to
send a valid `ClientHello` and the time from negotiation to server-side identity
binding. Expiry closes with WebSocket status 1008 and the fixed reason
`V2 handshake timeout` or `V2 authentication timeout`. Successful phase changes
and disconnect cancel their scheduled tasks. Deployment durations remain
explicit configuration and are not defined by the short virtual-time tests.

Before password copying or worker submission, the single-process gateway
applies cumulative fixed-window limits to total attempts, the resolved direct
socket peer, and a normalized account key. Key maps are bounded and fail closed
at capacity. A verified login clears only its account bucket. Denials use the
same generic `RATE_LIMITED` payload and expose neither limiting key. Forwarded
headers are not trusted; multi-gateway coordination remains an M5 Redis concern.

These messages are not allowed on a production route until WSS, origin policy,
metrics/log export, trusted-proxy policy, redaction, token rotation,
authenticated idle policy, and listener lifecycle are implemented and verified.

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
