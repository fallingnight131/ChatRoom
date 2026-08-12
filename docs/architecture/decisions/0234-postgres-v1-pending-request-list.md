# ADR-0234: Fail Closed on Incomplete Pending-Request Mappings

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Read incoming PENDING requests in one read-only repeatable-read PostgreSQL
transaction. First count every canonical pending row for the enabled recipient,
then load rows joined to both the V1 request-ID and requester-account mappings.
Require the mapped row count to equal the canonical count; otherwise fail the
entire list rather than hiding an un-actionable request.

Order by authoritative creation time and UUID descending, cap at 1,000, and
return no canonical UUIDs. Disabled requesters deliberately make reconciliation
fail until the compatibility policy is reviewed; they are not silently shown or
dropped. No route is activated.

## Verification

Disposable PostgreSQL tests cover exact request/requester IDs and profile fields,
then delete the request mapping and prove the whole read fails.
