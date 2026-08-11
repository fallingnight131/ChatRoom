# ADR-0087: Final V1 Import Input Gate

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The import command re-verifies source, backup, and proof before PostgreSQL commit,
but operators lacked a standalone final check between entering the maintenance
window and authorizing target writes. Backup-only verification does not compare
the current source, and preview introduces target access when the immediate goal
is to prove the final input set is unchanged.

## Decision

- Add `verify-final <source> <backup> <proof> <fingerprint>` as a read-only,
  no-PostgreSQL command.
- Require the operator-supplied fingerprint to be exact lowercase SHA-256 and to
  equal the proof. Re-read and reconcile the current V1 source, restored backup,
  proof fingerprint/row count/file hash/size, and complete planned identity rows.
- Return only fixed safe status, fingerprint, and row count. Normalize failures
  without printing paths, accounts, credentials, database errors, or hashes other
  than the explicitly non-secret source fingerprint.
- Reuse the exact verifier in `apply`; apply still re-verifies again inside the
  serializable PostgreSQL transaction immediately before proof persistence and
  commit.
- Do not claim this command proves quiescence. Operators must stop and independently
  confirm all V1 writers before running it. A post-check writer can still change
  the source and is therefore a stop condition.

## Consequences

The maintenance-window decision has a scriptable, target-independent checkpoint,
and accidental source drift is rejected before any PostgreSQL write. Operational
writer shutdown and full C++ server restore/launch timing remain rehearsal work;
this gate does not replace them.

## Verification

CLI tests create a WAL source, verified backup/proof, isolated restored copy, and
successful final check, then mutate the source and prove the same confirmation is
rejected with safe output. The migration and full Java workspace gates remain
required.

## Rollback

Remove the standalone command while retaining the identical validation inside
`apply`. No source, backup, proof, PostgreSQL schema, or imported row is changed
by `verify-final`.
