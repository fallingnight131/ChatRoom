# ADR-0334: Windows V2 Shared Authenticated Transport

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The Windows V2 product path already owns one authenticated WSS for session and
device management. The detached messaging application can encode reply and
history commands, but sending them through a second connection would split
authentication, resume, reconnect, and event ordering. Sending their responses
through the existing device codec would instead fail as protocol type confusion.

## Decision

- Keep one `chat.v2` WSS connection and authenticated session per Windows
  product controller. Do not create a messaging-only socket.
- Let the transport accept only bounded type 100, 102, and 105 command envelopes
  for its current authenticated session. Track at most 32 distinct messaging
  request IDs and remove a correlation only when its response or error arrives.
- Route correlated type 101/103 responses, correlated protocol errors, and
  session-bound type 104 events to the messaging application without passing
  them through the device codec. Device frames retain the existing session and
  device protocol path.
- Reject wrong-session, duplicate, unsupported, oversized, or unauthenticated
  messaging commands before socket dispatch. Treat malformed, wrong-session,
  and uncorrelated messaging responses as connection protocol failures.
- Clear all transport correlations whenever the protocol/socket is cleared.
  Durable resend intent remains solely in the SQLite-backed messaging
  application service.

## Consequences

Device management and messaging can share authentication and reconnect order
without sharing their domain codecs. The existing transport class name remains
temporarily narrow; product composition may depend on its generic messaging
frame API while a later focused rename can avoid broad churn.

This slice exposes transport hooks but does not yet instantiate the local
repository, messaging application, ViewModel, or reply panel in `ChatClient`.

## Verification

The transport test authenticates a session, rejects a wrong-session outbound
message, sends a messaging command on the existing socket, routes its correlated
response around device decoding, and retains the existing device-directory and
text-frame fail-closed checks. The full generated-binding gate builds and runs
the transport alongside the session, device, and messaging protocol tests.

## Rollback

Remove the messaging send API, correlation set, and authenticated frame routing.
The detached messaging layers and the existing device-management product path
continue to operate independently; no server, database, or wire migration is
required.
