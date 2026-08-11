# ADR-0074: Web V2 Protocol and Session State Machine

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The Web product now has generated V2 TypeScript bindings, but directly invoking
them from Vue stores would spread transport phases, request correlation, secret
handling, and server-response validation across UI code. Connecting the new
gateway immediately would also combine protocol correctness, reconnection,
credential UX, and rollout into one hard-to-reverse change.

## Decision

- Add a transport-independent TypeScript state machine under the Web V2 protocol
  boundary. Keep the existing V1 WebSocket and product path unchanged.
- Model hello, negotiation, fresh authentication, authenticated operation, and
  closure explicitly. Reject commands that are invalid for the current phase.
- Correlate every response/error with a bounded pending-request registry and
  require the registered message type and envelope kind. Preserve validated
  request and client-message correlation on the decoded application event.
- Enforce the server's frame, payload, identifier, text, page, and session
  constraints before data reaches application state. Treat server sequence and
  timestamp fields as authoritative response data, never client ordering input.
- Copy password bytes only for immediate Protobuf serialization and clear that
  owned copy. Retain the issued resume token only in memory, expose defensive
  copies, and clear retained bytes on closure. Durable token storage is not
  authorized by this decision.
- Keep WebSocket lifecycle, retry/backoff, resume-session commands, IndexedDB,
  Vue/Pinia integration, and traffic rollout as later independently verified
  slices.

## Consequences

The browser now has one testable protocol authority that can sit below either a
future WebSocket adapter or application orchestration. Invalid state, response
confusion, wrong-session data, oversized input, and malformed payloads fail
before UI state is updated. This adds TypeScript test tooling to the Web lockfile
but does not alter a user-visible path or make V2 production-ready.

## Verification

The Web gate type-checks the V2 source, runs deterministic binary/state-machine
tests plus the existing JavaScript suite, and builds the production Vite bundle.
Tests cover successful hello/authentication and all currently registered
messaging commands, defensive token access, invalid transitions, unknown or
mismatched request IDs/types, wrong sessions, malformed envelopes, and invalid
server negotiation.

## Rollback

Remove the unconnected state machine, its tests, and TypeScript test tooling.
The committed generated bindings, V1 Web client, and Java V2 gateway remain
unchanged.
