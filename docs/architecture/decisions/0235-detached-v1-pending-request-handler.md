# ADR-0235: Compose the Detached V1 Pending-Request Handler

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Add a strict bounded `FRIEND_PENDING_REQ`/`FRIEND_PENDING_RSP` JSON codec and
authenticated Netty handler to the detached V1 module. Use only the channel-bound
account, execute through the bounded directory worker, suppress stale results,
and close on malformed input, concurrent owned requests, saturation, projection
failure, or encoding failure without emitting a partial list.

The response preserves request/requester numeric IDs, requester profile, and
authoritative creation epoch milliseconds. Fixed-cardinality telemetry contains
counts and duration only. The product listener still does not install this module.

## Verification

Embedded-channel tests cover exact fields/time and failure paths. Disposable
PostgreSQL verifies login followed by friend and pending lists with no UUID leak.
