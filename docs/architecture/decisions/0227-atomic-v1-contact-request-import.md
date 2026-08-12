# ADR-0227: Import V1 Pending Contact Requests Atomically

- Status: Accepted
- Date: 2026-08-13
- Owners: Contacts, persistence, and V1 migration
- Related milestone: M3

## Context

ADR-0226 established a deterministic, pending-only V1 contact-request plan and
a re-verifiable source/backup capability. PostgreSQL must now distinguish a safe
first import, an exact rerun, a partially compatible earlier write, and a
conflicting canonical request or numeric compatibility mapping. A successful
result also needs durable proof independent of identity/conversation audits.

## Decision

- Add `contact_request_import_run` as an append-only audit whose constraints
  reconcile total source rows into pending/terminal counts and every pending row
  into inserted/already-present outcomes.
- Preview in a read-only repeatable-read transaction. Compare each deterministic
  request UUID, ordered requester/recipient direction, `PENDING` state, creation
  time, null resolution time, and V1 numeric mapping exactly.
- Treat a pending request in either direction for the same unordered pair, a UUID
  collision, a conflicting V1 mapping, or an absent/disabled participant account
  as a blocking target issue. Unrelated V2-native contact rows remain allowed.
- Apply under a serialized transaction with explicit target table locking. Insert
  only absent exact requests and mappings, then require complete post-write
  reconciliation.
- Re-verify current SQLite, protected backup, and physical proof immediately
  before writing the audit and committing. Store no paths, usernames, display
  names, credentials, or contact content in issues or audit rows.
- Keep the importer programmatically inactive: this slice adds no CLI command,
  runtime handler, supported-client route, or authority cutover.

## Alternatives Considered

- Use `ON CONFLICT DO NOTHING`: rejected because it cannot distinguish an exact
  rerun from conflicting request direction, time, state, pair, or mapping.
- Reuse the conversation-import audit: rejected because request and conversation
  reconciliation counts have different meanings and rollback boundaries.
- Import without locking and rely only on uniqueness constraints: rejected
  because a concurrent native request could produce an opaque partial failure
  instead of a deterministic pre/post comparison.

## Consequences

Verified pending request state can be written and rerun without duplicates, and
every committed apply has a constrained physical/logical proof. Conflicts fail
before commit and preserve the previous target. PostgreSQL still does not serve
V1 friend lists or own product traffic.

## Migration and Rollback

V015 is forward-only and additive. Before authority cutover, rollback uses the
previous binary while the unused audit table and any offline-imported data
remain. Reverting imported rows requires restoring the reviewed pre-import
PostgreSQL backup; ad-hoc deletes are not an accepted rollback mechanism.

## Verification

- disposable PostgreSQL 17 clean migration and same-database restart;
- audit count/hash/backup constraints;
- no-write preview and exact first apply;
- exact idempotent rerun with a second audit;
- direction and mapping conflicts block apply without a new audit;
- source drift during apply rolls back without a new audit.
