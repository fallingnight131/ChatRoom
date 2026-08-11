# ADR-0054: Atomic V1 Identity Import into PostgreSQL

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

A valid SQLite projection and backup are necessary but do not prove that the
PostgreSQL target is empty, compatible, or unchanged during an import. A partial
identity copy would make later authentication routing unsafe, especially when a
deterministic account UUID or exact username already belongs to different data.

## Decision

- Provide a read-only target preview before apply. Compare every planned account
  by deterministic UUID and exact username, and compare all credential,
  display-name, creation-time, and enabled-state fields for an idempotent match.
- Treat a UUID/username mismatch, any non-identical mapped account, and every
  unexpected target account as blocking. Reports contain counts plus legacy
  numeric IDs and fixed issue codes; they never contain usernames, password
  material, salts, or filesystem paths.
- Require a fresh reconciliation of the current SQLite source, backup contents,
  backup file hash/size, and backup proof before PostgreSQL apply. Re-run that
  verification before commit to detect source or backup drift during the target
  transaction.
- Serialize apply with a PostgreSQL table lock and `SERIALIZABLE` transaction.
  Insert only missing accounts, then re-read the whole target and require every
  planned field to match with no unexpected account.
- Persist a successful run's source fingerprint, backup hash/size/time, source
  count, inserted count, and already-imported count in
  `chat.identity_import_run` inside the same transaction. Do not store source
  paths or credentials in the audit row.
- Keep V1 authoritative. This API is migration infrastructure, not an enabled
  command, scheduled job, authentication route, or cutover.

## Consequences

- An empty dedicated target can be populated atomically, and repeating the same
  verified import is data-idempotent while creating a new audit run.
- Existing unrelated PostgreSQL accounts intentionally block apply. Merging two
  identity authorities requires a separate reviewed mapping decision.
- The table lock favors correctness over online target writes. That is acceptable
  before this inactive V2 slice owns traffic, but is not a general live-merge
  mechanism.
- Source quiescence and a final fingerprint check are still required at actual
  authority cutover; a V1 write after the final in-transaction read remains an
  operational cutover concern.

## Verification

Pure tests reject a changed source and a mismatched backup proof. The disposable
real-PostgreSQL gate previews an empty target without writes, applies two
credential generations, reconciles every account, persists the backup proof,
repeats without duplicate accounts, and proves a conflict leaves both account
and audit counts unchanged.

## Rollback

Any pre-commit failure rolls back account inserts and the audit row together.
At this additive stage, discard the inactive PostgreSQL database or import
transaction and continue using the untouched V1 SQLite authority and its
protected online backup. Production restore rehearsal and a quiesced cutover
runbook remain mandatory before traffic can switch.
