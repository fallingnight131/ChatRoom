# ADR-0044: V2 WebSocket Egress and Close Semantics

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The V2 gateway decoded binary WebSocket messages but emitted in-memory
`Envelope` objects. It also propagated malformed and oversized frame exceptions
without a deterministic client-visible close. A real listener must never expose
Java/Netty exception text or depend on an unspecified pipeline tail.

## Decision

- Encode every outbound V2 `Envelope` as one final binary WebSocket message.
  Reject an internally produced envelope that exceeds the same bounded wire
  size accepted inbound.
- Normalize known decoder and aggregator failures to gateway-owned
  `V2FrameException` categories, then consume those categories at the transport
  boundary.
- Close malformed Protobuf, unsupported frame types, invalid envelopes, and
  broken fragmentation with WebSocket status 1002 and the fixed reason
  `invalid V2 frame`.
- Close oversized complete or fragmented messages with WebSocket status 1009
  and the fixed reason `V2 frame too large`.
- Do not include parser messages, payload bytes, identifiers, exception class
  names, stack traces, or infrastructure details in close reasons.
- Continue propagating unknown exceptions so a later listener supervisor can
  record and handle genuine programming/infrastructure failures separately.

## Consequences

- The installed frame pipeline is now symmetric: binary WebSocket frame to V2
  envelope inbound and V2 envelope to binary WebSocket frame outbound.
- Known client-controlled framing failures end in a bounded, interoperable
  close rather than an exception escaping the pipeline.
- This does not enable a listener. TLS/WSS, origin checks, connection deadlines,
  admission controls, bounded workers, and listener lifecycle remain required.

## Verification

Embedded-channel tests parse outbound handshake/error frames back into the
expected envelope, verify one-envelope-per-binary-message encoding, and assert
fixed 1002/1009 close status and reason for text, malformed, policy-invalid,
oversized, and fragmented-overflow inputs.

## Rollback

Remove the encoder and terminal close handler from the unused V2 pipeline. V1
traffic and data remain authoritative and unchanged.
