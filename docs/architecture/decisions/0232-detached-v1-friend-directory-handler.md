# ADR-0232: Compose the Detached V1 Friend Directory Handler

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Add a strict bounded JSON codec and authenticated Netty handler for existing
`FRIEND_LIST_REQ`/`FRIEND_LIST_RSP`. Dispatch application/database work through
the existing bounded directory executor, bind identity exclusively from the
authenticated channel attribute, suppress late results after identity/channel
change, and reject concurrent owned requests.

On malformed input, saturation, projection failure, or encoding failure, close
with a fixed non-sensitive reason and never emit an empty/partial friend list.
Expose fixed-cardinality completed/failed/saturated diagnostics. Process-local
presence is derived from the same single-account connection registry and is
explicitly rebuildable.

Compose login, heartbeat, room directory, and friend directory in the detached
V1 module only. The product listener still does not install this module.

## Verification

Embedded-channel tests cover exact fields, authentication ownership, unrelated
traffic forwarding, malformed/failure/saturation closure, and active presence.
Disposable PostgreSQL tests prove login followed by room and friend lists with
legacy IDs, unread count, peer read watermark, pending count, and no UUID leak.
