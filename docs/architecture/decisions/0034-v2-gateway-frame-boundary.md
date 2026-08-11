# ADR-0034: V2 Gateway Frame Boundary

- Status: Accepted
- Date: 2026-08-11

## Context

The V2 envelope now has generated Java, C++, and TypeScript bindings. The Java
gateway needs a narrow transport boundary before authentication, routing, or
application dispatch is introduced. Unbounded or ambiguous WebSocket input
would make memory use and failure handling attacker-controlled.

## Decision

- V2 uses complete binary WebSocket messages containing exactly one Protobuf
  `Envelope`.
- The gateway aggregates fragmented binary messages with an explicit wire-size
  limit, then parses and applies the shared `EnvelopePolicy` before dispatch.
- Text and stray continuation frames are protocol errors. WebSocket control
  frames remain available to a dedicated control-frame handler.
- Transport failures carry stable internal reason categories. A later
  connection-policy slice will map those categories to bounded client errors
  and WebSocket close codes without exposing exception details.
- The decoder emits generated protocol objects only at the gateway boundary;
  the application core will receive transport-independent commands through a
  separate adapter.
- This slice does not bind a socket or claim a deployable gateway. V1 transport
  and clients remain unchanged.

## Consequences

- Oversized and malformed input is rejected before application work begins.
- Fragment handling and validation order are repeatable in embedded-channel
  tests without opening a network port.
- Authentication, rate limits, error responses, observability, and application
  dispatch are still required before the V2 listener can be enabled.

## Verification

- Compile with JDK 21 and all Java warnings treated as errors.
- Test complete and fragmented valid frames.
- Test text, malformed Protobuf, oversized, and policy-invalid frames.
- Test that a control frame is not consumed by the envelope decoder.

## Rollback

Remove the new Netty handlers and dependency. No listener, persistent data,
protocol field, or V1 behavior changes in this slice.
