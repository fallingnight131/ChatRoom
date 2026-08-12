# ADR-0250: Compose Detached V1 Friend Removal

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Compose strict bounded `FRIEND_REMOVE_REQ/RSP/NOTIFY` handling in the detached
Java V1 module. Parse exactly one target username, bind the actor to channel
identity, and execute one removal at a time off the Netty event loop. First and
exact-duplicate removal preserve the existing success response with target
username; only first removal schedules a notification to the target's current
authoritative process-local V1 connection.

The notification contains the authenticated actor's username and display name,
never client-supplied identity. Self removal retains the specific legacy error;
missing/invalid/non-friend targets retain the generic error. Malformed input,
concurrent command saturation, encoding failure, dependency failure, and stale
completion close safely instead of inventing a mutation result. Fixed telemetry
contains outcomes and elapsed time but no account identifiers or usernames.

This gives no multi-gateway notification guarantee; Redis routing remains M5.
The complete compatibility module remains detached from the product listener.

## Verification

Codec tests cover strict input plus compatible success/self/generic responses.
Embedded-channel tests prove authoritative first-only notification and retry
suppression. Disposable PostgreSQL verification proves removal across two real
imported logins, first-only notification, duplicate success, both friend-list
refreshes, inactive memberships, retained conversation entries, and no UUID
exposure.
