# ADR-0247: Compose Detached V1 Friend-Request Creation

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Compose strict bounded `FRIEND_REQUEST_REQ/RSP/NOTIFY` handling in the detached
Java V1 module. Parse exactly one target username, bind the requester to channel
identity, run one creation at a time through the bounded directory executor, and
suppress late results. First creation schedules one notification to the current
authoritative local recipient connection; exact duplicate returns success
without another notification.

Preserve the existing localized responses for missing/invalid target, self,
already friends, and reverse pending. Malformed input, executor saturation,
encoding failure, or dependency failure closes with a fixed safe reason. Fixed
telemetry distinguishes each domain outcome plus route-scheduled/no-local-route,
failure, saturation, and elapsed time without identities. Multi-gateway routing
remains M5 work and the product listener remains unchanged.

## Verification

Codec tests cover exact username parsing and localized reverse-pending response.
Embedded-channel tests prove first-only notification to the authoritative
recipient and duplicate suppression. The complete Java gate remains required;
real PostgreSQL request creation concurrency is independently covered by
ADR-0246. A combined real dual-login transport scenario remains the next gate.
