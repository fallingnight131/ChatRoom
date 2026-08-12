# ADR-0288: Import Verified V1 Room Capacity

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Follows: ADR-0287

## Decision

Make `room_settings(room_id, max_members)` part of the required V1 SQLite
conversation source schema. A missing settings row retains the server's legacy
default 50, while an explicit value is preserved. The effective value is
validated from 1 through 1000000 and included in the deterministic conversation
source fingerprint, so changing only room capacity changes the migration proof.
V025 expands the initial PostgreSQL constraint to the supported Web/Windows V1
client maximum of 1000000.

Carry the value in `V1RoomRow` and `PlannedV1Conversation`. Fresh GROUP imports
write it with the conversation. Target preview reads the admission policy and
blocks missing or different values. Post-write comparison requires exact
reconciliation before the physical backup is reverified and the append-only
import proof commits.

Databases that already imported conversations before V024 contain a backfilled
50. During this inactive migration stage, the importer may update a differing
source value only when the target still equals exactly 50, using one counted
compare-and-set in the same locked serializable transaction. Any other value is
treated as operator or application state and blocks migration. Preview and the
migration CLI expose `admission_policies_to_update`; a repeated run converges to
zero updates.

The product listener remains unchanged. Rollback removes the importer fields
and route use; V025 is forward-only and database rollback requires the
pre-migration backup or a reviewed forward repair.

## Verification

Planner tests prove capacity changes alter the fingerprint and invalid limits
block readiness. Query-only SQLite tests prove WAL-backed custom values are read
without writes and missing columns fail safely. Disposable PostgreSQL proves
fresh exact import, default-50 CAS migration, exact rerun convergence, conflict
on a non-default target, checked bounds, migration restart, migration CLI, and
existing gateway integration.
