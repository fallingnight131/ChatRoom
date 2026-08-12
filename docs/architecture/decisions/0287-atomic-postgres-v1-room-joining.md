# ADR-0287: Persist Atomic V1 Room Admission

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Follows: ADR-0286

## Decision

Add V024 `group_admission_policy`, keyed by a GROUP conversation through the
canonical `(id, kind)` identity. `max_members` is durable SQL truth, defaults to
the current V1 value 50, and is constrained to 1 through 100000. Upgrade
backfills existing groups; runtime room creation and verified V1 conversation
import create the policy beside the group.

Implement `PostgresLegacyV1RoomJoinAdapter` as a bounded-retry serializable
mutation. Inspection requires an enabled V1-mapped actor and a complete
GROUP/ROOM/policy projection. It returns active membership idempotently or the
optional typed Argon2id credential. Mutation locks the enabled actor, group, and
admission policy, compares the exact conversation and credential snapshot
already authorized by the application service, then checks active count and
inserts or reactivates one membership. Existing roles and read cursors survive
reactivation. A changed target or credential returns `ACCESS_CHANGED` and a
full room returns `ROOM_FULL` without mutation.

The policy lock gives all Java join writers one capacity serialization point;
serializable/unique conflicts retry in fresh bounded transactions. This does
not claim multi-service coordination with the old SQLite server. Product traffic
has not moved to Java.

The verified V1 conversation source does not yet carry customized
`room_settings.max_members`. Imported groups therefore receive 50 only as an
inactive default. The detached handler must not be activated until a physical-
source setting migration proves custom limits are preserved.

Rollback removes the unused adapter. Schema rollback requires the pre-V024
backup or a reviewed forward migration; production Flyway downgrade is not
automatic.

## Verification

Disposable PostgreSQL proves clean V024 migration and restart, policy creation
for runtime and imported groups, checked limit bounds, credential-snapshot
change rejection, existing-member idempotency, and two concurrent accounts
contending for one final place with exactly one committed admission.
