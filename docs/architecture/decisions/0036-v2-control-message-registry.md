# ADR-0036: V2 Control Message Registry and Handshake

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The V2 envelope deliberately carries a numeric `message_type` and opaque
payload. Enabling gateway dispatch without one authoritative registry would let
Java, Web, and Windows assign different meanings to the same number. Version
negotiation must also happen before authentication so an unsupported client can
receive a bounded, non-secret error.

## Decision

- Add `control.proto` beside the envelope as an authoritative generated schema.
- Permanently allocate control message types 1 (`ClientHello` command), 2
  (`ServerHello` response), and 3 (`ProtocolError` error). Removed values will be
  reserved and never reused.
- A client hello carries a protocol-version range, supported product platform,
  app version, and client device ID. It carries no credential or session token.
- Accept only Web and Windows enum values. Bound unauthenticated app-version and
  device identifiers by UTF-8 bytes before gateway state is allocated.
- Separate structural validity from version overlap: a well-formed future
  client receives `UNSUPPORTED_VERSION`, not a malformed-payload response.
- Generate Java, C++, and TypeScript bindings from all schemas in one task and
  preserve a shared ClientHello golden payload.
- Keep message-kind requirements in a central Java registry. The following
  gateway state-machine slice will parse registered payloads and reject message
  types that are not valid in the current connection state.

## Consequences

- All supported clients share stable control identifiers and field numbers.
- Authentication can evolve separately without putting secrets into the
  negotiation payload.
- This does not enable a listener, authenticate a user, create a device/session,
  or alter V1 behavior.

## Verification

- Java tests validate registry lookup, required envelope kinds, UTF-8 limits,
  supported/unsupported version ranges, and exact ClientHello bytes.
- Generated TypeScript and C++ bindings parse and re-emit the same ClientHello
  golden payload.
- The full Java and protocol-binding gates remain green.

## Rollback

No supported route uses these additive schemas. The registry and generated
build inputs can be removed before the V2 compatibility window begins; numeric
values must not be reassigned if any external test/client has shipped them.
