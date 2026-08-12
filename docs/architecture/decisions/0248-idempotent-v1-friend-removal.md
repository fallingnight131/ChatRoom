# ADR-0248: Define Idempotent V1 Friend Removal

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define a transport-independent V1 friend-removal use case. Bind the actor UUID
to authenticated server state and accept only one exact, untrimmed, non-control
target username of at most 128 UTF-8 bytes. Persistence must resolve that
username to an enabled V1-compatible target; the client cannot provide or
influence either canonical account UUID.

Removing a friend terminates both active memberships of the canonical DIRECT
conversation atomically. It does not delete the conversation, V1 friendship
mapping, messages, conversation entries, or read cursors. This retained durable
state lets an exact response-loss retry return success and lets a later accepted
friend request reactivate the existing relationship without fabricating a new
history.

Return first removal, exact duplicate, or typed rejection for missing target,
self removal, inactive/nonexistent friendship, and invalid target. A duplicate
success must not repeat `FRIEND_REMOVE_NOTIFY`; the target UUID and exact
username are internal routing/response context. This slice adds no database
adapter or route.

## Verification

Application tests prove server-bound actor propagation, exact username
preservation, invalid-input rejection before persistence, removal routing
context, and impossible self-result failure.
