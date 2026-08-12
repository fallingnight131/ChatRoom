# ADR-0276: Compose Detached V1 Private Read Handling

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0275

## Decision

Compose strict bounded V1 `MARK_FRIEND_READ` handling in the detached Java
compatibility module. Bind the actor to authenticated channel state, accept
only a positive mapped friendship ID, and execute at most one cursor mutation
per channel off the event loop. Preserve the V1 response-free shape for
successful, unchanged, access-denied, and invalid-ID business results.

After a successful durable result, encode `FRIEND_READ_NOTIFY` from only the
server-returned friendship, mapped peer, authenticated reader username, and
sequence-ordered V1 message watermark. Schedule it through the current
process-local peer registry. Do not route self-chat to the same channel. Exact
repeats may publish the same notification because receivers apply the watermark
monotonically and durable friend-directory state remains restart recovery.

Malformed, concurrent, saturated, dependency-failed, or stale work fails
closed. Fixed telemetry records only outcome, sequence delta, route scheduling,
duration, failure, and saturation; it contains no account or friendship
identity. This is not a delivery guarantee and introduces no cross-instance
routing.

The detached handler does not activate the product listener. Rollback removes
it from the detached pipeline; the monotonic persisted cursor and directory
projection remain valid.

## Verification

Handler tests prove authenticated actor binding, no sender response, mapped-peer
notification fields, exact-repeat re-publication, self-chat suppression,
business-denial silence, downstream pass-through, malformed closure, and
saturation closure. Disposable PostgreSQL proves a peer marks the newest direct
message read, the sender receives the notification, and a replacement login
recovers the identical `peerLastReadMessageId` from `FRIEND_LIST_RSP`.
