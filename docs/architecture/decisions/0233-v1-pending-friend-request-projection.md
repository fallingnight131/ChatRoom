# ADR-0233: Project V1 Pending Friend Requests as a Complete List

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define a transport-independent, recipient-scoped V1 pending-request use case.
It returns the existing numeric request/requester IDs, username/display name,
and authoritative creation time, bounded to 1,000 rows. Duplicate IDs or an
oversized result fail the whole request so accept/reject actions cannot become
ambiguous. SQL and JSON remain outside the application service.

This slice adds no runtime route and does not change request lifecycle semantics.

## Verification

Application tests cover server-bound recipient propagation, exact fields, fixed
bound propagation, and duplicate-ID failure.
