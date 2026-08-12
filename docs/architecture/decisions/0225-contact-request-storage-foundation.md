# ADR-0225: Add the Contact Request Storage Foundation

- Status: Accepted
- Date: 2026-08-13
- Owners: Contacts, persistence, and V1 migration
- Related milestone: M3

## Context

Accepted V1 friendships are imported as canonical DIRECT conversations, but V1
`friend_requests` are absent from PostgreSQL. Both supported clients use the
pending-request count after login and numeric request IDs for later accept or
reject commands. Returning zero or deriving requests from conversations would
silently lose durable user state.

## Decision

- Add canonical `contact_request` UUID rows owned by the contacts domain, with
  server-account requester/recipient foreign keys and explicit `PENDING`,
  `ACCEPTED`, `REJECTED`, or `CANCELLED` lifecycle.
- Require distinct accounts, terminal resolution time, and at most one pending
  request for an unordered account pair. Index pending requests by recipient and
  stable descending `(created_at,id)` order.
- Add `legacy_v1_contact_request_map` as an isolated positive numeric-ID
  compatibility projection. Do not add legacy IDs to the canonical row.
- Create only the forward schema in this slice. No importer, repository, runtime
  handler, or traffic authority is activated.

## Alternatives Considered

- Always report zero pending requests: rejected because it would hide imported
  user state and change old-client behavior.
- Treat every DIRECT conversation as a request: rejected because accepted
  relationships and pending intent have different authorization/lifecycle.
- Keep request IDs as numeric canonical identifiers: rejected because it would
  leak a temporary V1 representation into the V2 contacts model.

## Consequences

PostgreSQL can now represent contact-request truth and the non-invertible V1
identifier mapping. The additional tables are dormant until the verified import
and contacts application boundaries are implemented.

## Migration and Rollback

V014 is forward-only and additive. Before authority cutover, rollback uses the
previous application binary while the unused tables remain. After request data
is imported or written, rollback requires the documented database restore or a
separately reviewed forward migration; tables must not be dropped casually.

## Verification

- `python3 tools/verify_m0.py --postgres`
- clean migration and same-database restart;
- self-request, reverse-pending duplicate, invalid lifecycle, duplicate mapping,
  and non-positive legacy-ID rejection.
