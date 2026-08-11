# ADR-0053: Verified V1 SQLite Online Backup

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

Copying only the V1 main SQLite file can omit committed WAL pages. An identity
import apply needs a restorable, independent database artifact tied to the exact
validated source plan, not merely a file that happened to exist before a run.

## Decision

- Use Xerial's SQLite online backup API from a URI read-only/query-only source
  connection. This reads a transactionally consistent database including
  committed WAL state without mutating the source.
- Refuse invalid/empty identity plans before backup. Write first to a randomized
  temporary file in the destination directory and never overwrite an existing
  requested destination.
- Reopen the temporary backup through the same read-only source adapter. Require
  `quick_check`, current users schema, a valid plan, identical account plan, and
  identical source fingerprint.
- Calculate the backup file SHA-256 and byte length, then move it to the new
  destination atomically when supported. Return a proof containing source
  fingerprint, backup hash, identity row count, byte length, and server time.
- Delete incomplete temporary output on failure. Fixed exceptions do not expose
  source/destination paths or row material.
- This proof authorizes only the identity slice it fingerprints. Later room,
  message, contact, and attachment imports need their own source projections and
  reconciliation before they can use the same full backup as rollback evidence.

## Consequences

- Identity apply can require a concrete backup artifact whose logical identity
  contents and physical hash were verified before target writes.
- The backup is not itself a cutover: V1 remains authoritative, and restore
  rehearsal plus source quiescence/fingerprint recheck remain required.
- An operator must store the backup and proof together in access-controlled
  storage; neither belongs in Git.

## Verification

Tests create a WAL source with a committed user, run online backup while the
source connection remains open, and prove source and backup plans match exactly.
They verify hash/size/time proof fields, refusal to overwrite an existing file,
unchanged existing bytes, and no artifact for an empty invalid source.

## Rollback

Remove the unused backup service. It does not write PostgreSQL or change V1
authority. Delete test artifacts only; production backup retention is an
operator-controlled rollback requirement.
