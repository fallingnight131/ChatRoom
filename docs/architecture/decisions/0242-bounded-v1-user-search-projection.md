# ADR-0242: Define a Bounded V1 User-Search Projection

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define transport-independent V1 user search with a server-bound excluded account,
a trimmed non-control keyword of at most 256 UTF-8 bytes, and at most 20 results.
The durable search port returns only enabled V1-compatible identities with their
positive numeric ID, username, display name, and canonical account UUID. The
application layer joins process-local presence separately and never exposes the
UUID.

Treat the keyword as a literal case-insensitive substring at persistence rather
than allowing client `%` or `_` to become SQL wildcards. Reject empty, oversized,
or control-bearing input before persistence. Fail the complete projection on
self inclusion, duplicate account/numeric ID/username, excess results, or
presence outside the durable result set. This slice adds no database adapter or
route.

## Verification

Application tests prove server-bound exclusion, trimming, result bounds,
presence joining, invalid-input rejection before persistence, and fail-closed
self/duplicate projections.
