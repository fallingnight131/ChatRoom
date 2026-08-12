# ADR-0263: Persist V1 Room Messages Atomically

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0262

## Decision

Implement V1 room text/emoji submission as a bounded-retry serializable
PostgreSQL transaction. Resolve the positive V1 `ROOM` mapping to a GROUP
conversation, require an enabled mapped actor, its non-revoked authenticated
device, and active membership, and lock the canonical conversation before
sequence allocation.

Create the `MESSAGE` entry, canonical UTF-8 type-1 message, and positive ROOM
compatibility mapping with preserved `text`/`emoji` presentation in one
transaction. V022 allocates runtime room-message IDs downward from signed
32-bit maximum; imported IDs remain unchanged, occupied IDs are skipped, and
rollback gaps are harmless. Exact retries validate conversation, device,
payload/hash/type, and mapping before returning the original result. Conflicting
reuse, denial, or failure consumes no conversation sequence.

Serialization and uniqueness races retry in fresh transactions up to a fixed
bound. The adapter emits no transport message and does not activate a route.
Rollback requires the pre-V022 backup if schema downgrade is required; otherwise
the unused adapter and sequence may remain additive.

## Verification

Disposable PostgreSQL tests prove clean V022 migration/restart and descending
sequence shape, concurrent first/duplicate convergence, one canonical/mapped
row, preserved presentation, exact identity, conflicting retry, outsider and
left-member denial, and unchanged sequence after rejected work.
