# ADR-0006: Bound V1 Connection Resource Use

- Status: Accepted
- Date: 2026-07-11
- Owners: project maintainers
- Related milestone: M1

## Context

The V1 TCP parser previously cleared an oversized buffer but kept the connection
open, WebSocket messages had no application-configured maximum, malformed input
had no disconnect policy, and outbound chat sockets had no pending-byte high
water mark. A client could retain memory, consume parser/dispatcher work, or
cause an unbounded slow-consumer queue.

The current clients still Base64-encode files up to 8 MiB and use Base64 chunks
for larger uploads. Any compatible limit must leave room for that expansion and
the JSON envelope.

## Decision

- Set the maximum V1 JSON message/frame to 16 MiB for both TCP and WebSocket.
  This accommodates an 8 MiB Base64 payload while rejecting the previous,
  unnecessarily broad 50 MiB allocation surface.
- Give the TCP socket a matching bounded read buffer. Reject an oversized length
  prefix immediately instead of waiting for or retaining the payload.
- Distinguish incomplete, malformed, oversized, and complete TCP frames. Close a
  connection after three malformed JSON/envelope messages.
- Require a non-empty message `type` and object-valued `data`. Unknown non-empty
  V1 types remain ignored for forward-compatible optional extensions.
- Allow at most 60 complete messages per connection per one-second window. A
  larger burst disconnects the connection; no new wire message type is added.
- Set a 24 MiB pending-write high water mark for TCP and WebSocket. Before adding
  another response, disconnect a slow consumer whose queued bytes would cross
  that mark. Remove per-message synchronous TCP `flush()` calls.
- Emit structured category, numeric user ID, and transport logs at every forced
  disconnect. Do not log payloads or credentials.

## Alternatives Considered

- Retain 50 MiB because it already existed: rejected because active clients
  switch to chunking above 8 MiB and do not require a 50 MiB JSON object.
- Return a new protocol-error frame before closing: deferred to V2 because a
  peer causing backpressure may not receive it and old clients do not understand
  a new required error contract.
- Drop outbound messages while keeping the socket alive: rejected because it
  creates silent message loss and an inconsistent client view.
- Apply limits only at a reverse proxy: rejected because direct TCP has no proxy
  requirement and application invariants must hold in every deployment.

## Consequences

An old client sending a nonstandard JSON frame above 16 MiB is disconnected. The
documented 8 MiB inline-file and 4 MiB chunk paths remain below the limit. A
client that sends more than 60 messages inside one second may reconnect, but the
server will not process the excess burst. This is a connection resource guard,
not yet a complete account/IP authentication-abuse policy.

The pending-byte check makes overload behavior explicit: one slow device is
disconnected instead of consuming unbounded process memory or slowing healthy
recipients. V2 should replace this coarse policy with bounded per-session
mailboxes and observable delivery/resume semantics.

Rollback restores known denial-of-service and memory-amplification paths and is
not suitable for an Internet-facing deployment.

## Verification

`Tests/v1_transport_limits_test.py` uses real TCP connections to verify:

- immediate disconnect on an oversized length prefix;
- disconnect after malformed JSON or envelopes;
- forward-compatible ignore behavior for an unknown type;
- disconnect for a 61-message one-second burst;
- successful creation of an 8 MiB V1 inline file; and
- slow-consumer disconnect after repeated 8 MiB downloads without reads.

The existing smoke and authorization suites run first to protect positive and
negative V1 behavior.
