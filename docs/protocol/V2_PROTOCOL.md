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
handler. The listener, handshake/authentication policy, rate limits, and safe
client error/close mapping are not enabled yet, so V2 still has no production
route.

## Control message registry

Registry values are permanent and a removed value must be reserved rather than
reused:

| `message_type` | Payload | Required envelope kind | Direction/state |
| ---: | --- | --- | --- |
| 1 | `ClientHello` | command | client to server, first application frame |
| 2 | `ServerHello` | response | server to client after successful negotiation |
| 3 | `ProtocolError` | error | server to client for a bounded safe protocol failure |

`ClientHello` declares a minimum/maximum protocol generation, Web or Windows
platform, app version, and client-device ID. App version is limited to 64 UTF-8
bytes and device ID to 128 UTF-8 bytes. It intentionally contains no credential
or resumable session secret. A structurally valid range that does not include V2
is an unsupported-version result; it is not treated as malformed input.

`ServerHello.connection_id` is diagnostic connection identity, not an
authenticated session. Authentication message types and secret-handling rules
will be added in the next vertical slice before a listener is enabled.

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
envelope and ClientHello identically. The generated C++ binding is compiled against the matching
SHA-256-pinned Protobuf/Abseil test runtime and must parse and re-emit those same
bytes. This completes envelope-level cross-language evidence; feature payload
registries still require their own additive compatibility tests.
