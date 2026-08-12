# ADR-0240: Establish Accepted V1 Friendship Atomically in PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Implement V1 request acceptance in one `SERIALIZABLE` PostgreSQL transaction.
Resolve and lock the mapped request, require both accounts enabled and the
authenticated account to be its recipient, then create or reuse the canonical
ordered DIRECT conversation. The transaction creates or reactivates exactly
the two memberships, ensures exactly one FRIENDSHIP compatibility mapping, and
only then changes `PENDING` to `ACCEPTED` with database time.

V017 adds a non-cycling sequence that allocates runtime V1 friendship IDs from
`2147483647` downward. Historical V1 imports allocate upward, so opposite ends
reduce collision scanning; the adapter still skips every occupied mapped ID.
Sequence gaps after rollback are expected and carry no meaning. Exhaustion
fails the whole acceptance. UUID pair ordering follows PostgreSQL unsigned hex
ordering rather than Java's signed-long `UUID.compareTo` behavior.

An `ACCEPTED` retry succeeds only after revalidating the DIRECT pair, both active
memberships, and its FRIENDSHIP mapping. Wrong recipients, missing mappings,
disabled participants, and other terminal states reject generically. Incomplete
accepted state is an infrastructure failure, never a fabricated success. No
route is enabled.

## Verification

Disposable PostgreSQL tests validate V017 and its bounds, wrong-recipient
denial, first acceptance, database resolution time, two active members, exact
runtime mapping, and the fully revalidated duplicate result. The full migration,
restart, persistence, migration-CLI, and real-database gateway gate passes.
