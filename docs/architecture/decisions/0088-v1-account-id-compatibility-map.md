# ADR-0088: V1 Account ID Compatibility Map

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

V1 clients identify users with positive SQLite integer IDs, while the V2 domain
and PostgreSQL schema use UUID account IDs. The deterministic one-way import can
derive a UUID from a V1 ID, but the resulting UUID cannot be safely inverted when
a V1 response needs its original `userId`. Adding the old ID to the core account
model would spread a temporary transport concern into V2 application behavior.

## Decision

- Add `chat.legacy_v1_account_map` as a compatibility projection with one unique
  positive V1 ID per unique account UUID and an account foreign key with cascade
  deletion.
- Keep the projection outside `chat.account` and the application identity model.
  Only V1 boundary adapters and the verified import may depend on it.
- Make mapping creation part of the same serializable import transaction as the
  account rows and import proof. Lock, compare, and reconcile both tables before
  commit.
- Permit an exact pre-existing imported account with no mapping to receive its
  deterministic mapping on a repeated verified apply. Reject any conflicting
  V1-ID/account association before writes.
- Do not expose this mapping in V2 protocol fields or use it as a new identity
  allocator. V2-native accounts have no V1 mapping.

## Consequences

The future V1 JSON gateway adapter can reproduce the numeric login identity
without weakening the UUID-based V2 model. Import reruns can repair the additive
mapping for exact accounts created by an earlier M3 build. The compatibility
table must remain while any V1 client route is supported.

## Verification

The real PostgreSQL gate migrates through V005, checks table constraints, applies
both V1 credential generations, verifies exact mappings, repeats without
duplicates, and proves a conflicting mapping blocks preview and apply. The
migration CLI command-boundary test also requires mapping creation.

## Rollback

Before V1 routing is enabled, rollback may remove the unused V005 table only by
restoring a pre-migration database or applying a separately reviewed forward
migration. Once V1 traffic depends on it, retain the table until the documented
V1 compatibility window ends; disabling a route does not justify dropping data.
