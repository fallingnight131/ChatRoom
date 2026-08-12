# ADR-0246: Create V1 Friend Requests Atomically in PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Implement friend-request creation in a `SERIALIZABLE` PostgreSQL transaction.
Require the authenticated requester and exact target username to resolve to
enabled V1-mapped accounts. Check active canonical DIRECT membership first, then
lock the unordered account pair's PENDING request. Same-direction pending is an
exact duplicate success; reverse pending is a typed denial. A new request and its
numeric compatibility mapping commit together.

V018 adds a non-cycling positive 32-bit request-ID sequence descending from
`2147483647`. Historical imports grow upward; the adapter also skips occupied
IDs, and rollback gaps are expected. Retry up to three fresh transactions for
PostgreSQL serialization or unique-conflict SQL states so same/opposite-direction
concurrent creation converges to the durable row's typed outcome instead of an
internal error. Exhausted retries fail closed. No route is enabled.

## Verification

Disposable PostgreSQL tests validate V018 bounds, exact-case target resolution,
self/missing/reverse outcomes, and a two-thread same-direction race producing
exactly one first acceptance, one duplicate acceptance, one PENDING row, and one
valid numeric mapping. The complete PostgreSQL gate passes.
