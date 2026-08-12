# ADR-0278: Project V1 Room Search from PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0277

## Decision

Implement V1 room search as a read-only repeatable-read PostgreSQL snapshot.
Require the authenticated actor to remain enabled and V1-mapped before reading.
A positive signed-32-bit decimal keyword selects only that exact ROOM mapping;
all other keywords use a case-insensitive literal title substring with SQL LIKE
metacharacters escaped. Results order by V1 room ID and canonical identity and
are bounded in SQL.

Select only mapped GROUP conversations, count active membership rows, and
resolve the creator from exactly one active canonical OWNER whose enabled
account has a V1 mapping. A candidate with a missing creator projection fails
the whole query. Multiple owners produce duplicate canonical results that the
application boundary rejects, rather than arbitrarily selecting one.

This adapter is not composed into a handler and does not activate a product
route. Rollback removes the unused adapter.

## Verification

Disposable PostgreSQL proves exact numeric room-ID lookup does not degrade into
title search, case-insensitive title lookup, literal `%`/`_` matching, active-
member counting, mapped creator projection, incomplete-creator failure,
disabled-actor failure, and invalid limit rejection. The complete PostgreSQL
migration and integration gate passes.
