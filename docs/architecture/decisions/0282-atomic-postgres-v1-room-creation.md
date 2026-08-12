# ADR-0282: Persist V1 Room Creation Atomically

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0281

## Decision

Add V023 with an optional GROUP-only Argon2id join credential, a V1 room-
creation idempotency record, and a descending positive signed-32-bit runtime
ROOM ID allocator. The idempotency record binds `(actor_account_id,
client_request_id)` to one normalized title, optional versioned keyed password
tag, canonical conversation, and OWNER membership. Database constraints reject
non-Argon2id join credentials, malformed/unversioned tags, non-GROUP credential
targets, and non-OWNER creation records.

Implement creation as a bounded-retry serializable PostgreSQL transaction.
Require the actor to be enabled and V1-mapped, then atomically insert the GROUP
conversation, active OWNER membership, optional slow password hash, positive
ROOM mapping, and idempotency record. Allocate runtime room IDs downward, skip
occupied imported mappings, and treat sequence gaps after rollback as harmless.

On retry, lock and validate the complete durable result. Compare normalized
title normally and optional keyed tag with a constant-time primitive; never
compare freshly salted password hashes. Exact matches return the original IDs
with `duplicate=true`; title/password-tag reuse conflicts. Missing credentials,
inactive ownership, wrong kind, invalid mappings, and disabled actors fail
closed. The adapter emits no transport event and does not activate a route.

Rollback before authority cutover removes the unused adapter while leaving the
additive V023 schema. Schema downgrade requires the pre-V023 backup; Flyway does
not run destructive down migrations.

## Verification

Disposable PostgreSQL proves clean V023 migration/restart, sequence bounds,
concurrent first/duplicate convergence, occupied-ID skipping, exact GROUP/OWNER/
ROOM/credential state, open-room creation, title and keyed-tag conflicts,
disabled-actor denial, invalid hash/tag constraints, and fail-closed recovery
when a protected room's credential is missing. The complete PostgreSQL gate
passes.
