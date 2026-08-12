# ADR-0226: Verify V1 Contact-Request Input Before Target Writes

- Status: Accepted
- Date: 2026-08-13
- Owners: Contacts, persistence, and V1 migration
- Related milestone: M3

## Context

V1 stores pending, accepted, and rejected friend-request rows, but it has no
trustworthy resolution timestamp. The PostgreSQL contact model requires a
resolution time for terminal states, while accepted relationships are already
preserved as canonical DIRECT conversations. Importing terminal rows would
therefore require fabricated facts or duplicate relationship state.

Pending requests still affect both supported clients after login and retain a
numeric V1 request ID for accept/reject compatibility. Their import must use the
same protected whole-file SQLite backup as identity and conversation migration,
and must fail closed when the source graph is contradictory.

## Decision

- Read only V1 user IDs, friendship pairs, and friend-request identity, status,
  direction, and creation time through WAL-aware query-only SQLite access.
- Require `quick_check = ok` and the current required columns before planning.
- Deterministically map each positive pending V1 request ID to a canonical UUID,
  and map its participants through the existing deterministic account mapping.
- Block target comparison for missing users, self requests, invalid IDs or
  timestamps, unsupported statuses, pending requests between existing friends,
  duplicate IDs, or multiple pending directions for one unordered pair.
- Count accepted/rejected rows in the source fingerprint and reconciliation, but
  do not write them to PostgreSQL. Accepted truth remains the imported DIRECT
  conversation; V1 cannot supply the required terminal resolution timestamp.
- Issue a re-verifiable input capability only when the current source and the
  protected backup produce exactly equal contact plans and the physical backup
  hash and size still match their identity-backup proof.
- Add no target write, runtime handler, or product route in this slice.

## Alternatives Considered

- Import terminal rows using their creation time as resolution time: rejected
  because it invents a user-visible historical fact.
- Ignore all friend-request rows: rejected because pending requests are durable
  user state used by Web and Windows.
- Trust only the logical source fingerprint: rejected because it would not prove
  that the operator-protected backup artifact is the verified file.

## Consequences

The next PostgreSQL importer can consume a deterministic pending-only plan and
can recheck it inside the target transaction. Terminal-row counts remain visible
for migration reconciliation without creating misleading canonical records.
The Java V1 compatibility route remains inactive and PostgreSQL remains
non-authoritative.

## Migration and Rollback

This slice adds Java planning/verification code only and changes no database or
traffic state. Rollback is removal of the unused code. A later write path needs
its own forward audit migration, transactional reconciliation, and ADR update.

## Verification

- deterministic and order-independent planning;
- invalid graph/status/time/identifier negative cases with non-sensitive issues;
- WAL-visible query-only SQLite extraction with no source writes;
- current-source versus protected-backup drift rejection;
- physical backup hash/size mismatch rejection.
