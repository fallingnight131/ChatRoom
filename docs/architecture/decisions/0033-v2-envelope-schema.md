# ADR-0033: V2 Protobuf Envelope Schema

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

V1 uses a large JSON message-type surface shared by handwritten C++ and
JavaScript code. M3 needs one generated protocol source while preserving V1
compatibility and preventing transport fields from becoming domain truth.

## Decision

- Use Protobuf binary messages for V2 and begin with the stable envelope in
  `Backend/protocol-v2/src/main/proto/chat/v2/envelope.proto`.
- Pin Protobuf Java/compiler 4.35.1 and Gradle plugin 0.10.0. Generated Java is
  build output; the checked-in `.proto` is authoritative. Commit dependency
  locks for every resolved configuration.
- Permanently assign envelope field numbers for protocol version, kind, numeric
  message type, request ID, session ID, client message ID, diagnostic sender
  time, and opaque typed payload bytes.
- Enforce structural limits before application dispatch. Sender time and
  session text are never treated as authority without authenticated server
  state.
- Add the payload registry and generated C++/TypeScript bindings as subsequent
  slices from the same schema source. The TypeScript generator/runtime have an
  independent npm lockfile. No supported V1 client changes in this decision.

## Compatibility and Rollback

The schema is additive and not routed in production. Rolling back removes the
V2 build artifacts without changing V1 behavior or data. Once a released V2
client exists, field numbers and published message-type meanings cannot be
reused; incompatible evolution requires a new protocol generation.

## Verification

Java generation runs inside the standard Gradle `check`. Tests validate the
wire-compatible golden envelope, round trip, UTF-8 byte limits, required
routing fields, and event/command request-ID rules. The binding gate generates
C++/TypeScript output and proves Java/TypeScript golden compatibility. Compiling
the generated C++ with the pinned runtime and parsing that golden envelope
remains the next acceptance gate.
