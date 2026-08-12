# ADR-0291: Persist V1 Room Leaving and Group Lifecycle

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Follows: ADR-0290

## Decision

Add V026 `group_lifecycle`, keyed to the canonical GROUP `(id, kind)` through a
database-enforced composite foreign key. Existing groups are backfilled and an
`AFTER INSERT` trigger creates the row for every future GROUP regardless of the
writer. `closed_at IS NULL` means active; a timestamp means durably dissolved.
The trigger body schema-qualifies its target so runtime `search_path` cannot
change its behavior.

Implement V1 room leave as a bounded-retry serializable transaction. It locks
the enabled mapped actor, GROUP/ROOM target, lifecycle, actor membership, all
active memberships, and their account projections. The transaction requires
exactly one active owner and complete enabled V1 mappings. An ordinary member
leave ends only that membership. An owner leave promotes an administrator
before a member, then orders by `joined_at` and canonical UUID. Last-member
leave ends membership and sets `closed_at`; it does not delete the conversation,
messages, entries, attachments, mappings, or audit rows. A retained inactive
membership makes an exact retry successful without repeating succession.

Fail closed on dissolved GROUPs in the generic conversation directory, generic
message/history authorization, attachment register/authorize/complete, and all
existing V1 room search, join, audience, message, history, read, and recall
adapters. GROUP reads also require the lifecycle row, so partial schema state
does not accidentally mean active. DIRECT behavior is unchanged.

The handler remains absent and the product listener remains unchanged. Rollback
before activation removes the adapter and route consumers. V026 is forward-only;
schema rollback requires a pre-V026 database restore, not an older binary.

## Verification

The real PostgreSQL gate proves clean V001--V026 migration, same-database
restart/checksum validation, automatic lifecycle rows, non-member and disabled
actor denial, administrator-first owner succession, exact-retry suppression,
last-member dissolution, retained canonical/mapping rows, and exclusion from
search, join, directory, audience, history, and read paths even after a corrupt
manual membership reactivation. Migration CLI and detached gateway PostgreSQL
integration tests also pass.
