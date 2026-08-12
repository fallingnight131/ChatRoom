# ADR-0245: Define Server-Resolved Idempotent V1 Friend Requests

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define a transport-independent V1 friend-request creation use case. Bind the
requester UUID to authenticated server state and accept only one exact,
untrimmed, non-control target username of at most 128 UTF-8 bytes. Persistence
must resolve that username to an enabled V1-compatible recipient; the client
cannot provide or influence either canonical account UUID.

Return first acceptance, exact same-direction duplicate, or typed rejection for
missing target, self request, existing friendship, reverse pending request, and
invalid target. Treat a same-direction PENDING row as idempotent success to make
response-loss retry safe, but mark it duplicate so transport never repeats
`FRIEND_REQUEST_NOTIFY`. A reverse PENDING row remains the legacy instruction to
process the incoming request. Accepted results retain recipient UUID only as
internal notification-routing context. This slice adds no database adapter or
route.

## Verification

Application tests prove server-bound requester propagation, exact username
preservation, invalid-input rejection before persistence, accepted recipient
routing context, and impossible self-result failure.
