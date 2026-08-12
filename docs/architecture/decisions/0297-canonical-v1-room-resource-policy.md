# ADR-0297: Import complete V1 room limits into canonical GROUP policies

- Status: Accepted
- Date: 2026-08-13

## Context

ADR-0296 requires a successful V1 settings read to return all four durable
limits. PostgreSQL already stores `max_members` in `group_admission_policy`, but
the legacy per-file size, total file space, and file-count limits existed only
in SQLite. Defaults cannot stand in for customized source values during Java
cutover.

## Decision

V027 adds one `group_resource_policy` row per GROUP, keyed through the canonical
conversation kind. It stores checked JSON-safe `max_file_size`,
`total_file_space`, and positive `max_file_count`. Existing GROUPs are
backfilled with legacy defaults and an insert trigger gives every future GROUP
one complete row. Member capacity remains in `group_admission_policy` because
it participates directly in serialized admission decisions.

The verified conversation import now requires all four migrated SQLite
`room_settings` columns, incorporates their effective values into the source
fingerprint, validates them before target access, and compares both policy rows.
A pre-existing untouched resource-policy default may be replaced only through
a counted compare-and-set; any non-default mismatch blocks import. Fresh room
imports receive their exact source policy in the same serializable transaction.

The read adapter returns settings only for an enabled, mapped actor with active
membership in an active mapped GROUP, and joins both complete policy rows in one
repeatable-read transaction.

## Consequences

- Runtime-created rooms retain V1-compatible defaults without application-side
  synthesis.
- Imported custom limits are reproducible, auditable, and restart-safe.
- Resource limits are canonical policy, not attachment usage accounting; used
  bytes remain a separate attachment/file projection.
- Settings mutation stays inactive pending atomic cleanup and administration
  semantics.
