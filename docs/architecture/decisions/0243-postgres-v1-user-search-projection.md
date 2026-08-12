# ADR-0243: Search Only Enabled Mapped V1 Accounts in PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Implement the V1 user-search port as one read-only PostgreSQL query joining
`account` directly to `legacy_v1_account_map`. Exclude the authenticated account
and disabled accounts in SQL, use case-insensitive substring matching over
username/display name, order deterministically by C-collated username then
numeric ID, and apply the caller-supplied bounded limit in SQL.

Escape backslash, `%`, and `_` before constructing the `ILIKE` pattern so a V1
keyword remains literal text. V2-native accounts without a compatibility mapping
are absent rather than projected with invented IDs. The adapter returns canonical
UUIDs only to the application layer for presence joining; transport never sees
them. No route is enabled.

## Verification

Disposable PostgreSQL tests cover case-insensitive matching, deterministic mapped
IDs, self exclusion, disabled mapped-account exclusion, unmapped native-account
exclusion, literal `%`, empty results, and invalid limits. The full PostgreSQL
migration/restart/persistence/CLI/gateway gate passes.
