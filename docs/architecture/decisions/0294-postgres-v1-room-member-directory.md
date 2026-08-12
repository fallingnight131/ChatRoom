# ADR-0294: Project Authorized V1 Room Members from PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Follows: ADR-0293

## Decision

Implement the room-member query in one repeatable-read transaction. Resolve the
legacy ROOM mapping only while the enabled mapped actor has active membership
and the GROUP lifecycle is active. Then read active members in deterministic
username/account order with the application-provided `limit + 1`.

Every active member must reference an enabled account and positive V1 account
mapping. Partial projection fails the entire request rather than hiding a member.
Return canonical account IDs only to the application presence boundary; they
remain absent from the wire. No new schema is required.

The handler remains absent and the product listener unchanged. Rollback removes
the unused adapter with no data effect.

## Verification

Disposable PostgreSQL proves deterministic MEMBER/OWNER projection and denial
for an outsider and after lifecycle closure. Application tests separately prove
overflow, projection consistency, and presence rules.
