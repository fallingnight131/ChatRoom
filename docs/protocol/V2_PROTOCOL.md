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
envelope identically. C++ generation is active; compiling it against the pinned
C++ runtime and parsing the same golden bytes is the remaining cross-language
gate before this envelope slice is complete.
