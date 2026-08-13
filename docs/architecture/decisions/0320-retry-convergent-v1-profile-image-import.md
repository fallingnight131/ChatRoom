# ADR-0320: Retry-Convergent V1 Profile Image Import

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3
- Depends on: ADR-0318, ADR-0319

## Context

The proof-bound V1 extractor and independent verifier can produce trusted
account/room avatar metadata and canonical content-addressed PNG objects. The
remaining migration crosses two systems that cannot share a transaction:
private object storage and PostgreSQL. A crash can occur after one or more
create-only object writes but before metadata commit, and an operator may retry
the same bundle after an unknown outcome.

## Decision

- Require the independently verified export, its explicitly confirmed manifest
  SHA-256, the matching SQLite backup proof, completed identity/conversation
  mappings, and real-provider capability evidence before import.
- Preview every ACCOUNT/ROOM target and reject missing, duplicated, disabled,
  wrong-kind, or conflicting target pointers before provider writes.
- Upload each unique canonical object with checksum-bound create-only semantics.
  An exact existing object is success; conflicting evidence is failure. Never
  hold a PostgreSQL transaction across provider calls.
- After all provider evidence succeeds, use one serializable PostgreSQL
  transaction to register exact object metadata, create version-1 pointers only
  where no pointer exists, and persist one immutable import run plus one entry
  for every present or explicitly absent source target.
- Make `manifest_sha256` unique. An exact retry reconciles the entire retained
  run and target/object state and returns the original run; a differing target
  state fails without partial database writes.
- Historical import entries are migration audit, not user mutation events. Do
  not fabricate `profile_image_change_audit` actors or client notifications.
- Provider objects left by a crash before the database transaction remain safe,
  immutable, and retry-convergent. The operator must retry the same confirmed
  manifest; abandonment requires a separately reviewed prefix inventory and
  cleanup procedure, never blind deletion.

## Consequences

Provider and database state cannot become atomically visible, but the ordering
prevents PostgreSQL from referencing an object without exact provider evidence.
The content-addressed create-only write makes restart cheap and deterministic.
The import audit can be large because absence is recorded explicitly; this is
intentional evidence that missing source bytes were not silently discarded.

V042 adds `profile_image_import_run` and `profile_image_import_entry`. It is an
additive expansion and does not activate Java authority or the detached V1
avatar handlers. The read-only pre-provider planner is implemented and checks
all account/room mappings, target availability, existing pointers, exact
registered object evidence, active cleanup claims, and prior manifest runs.
The inactive upload pass now rechecks bounded object bytes and requires exact
create-only Provider results for every unique manifest object; database metadata
never substitutes for Provider evidence. The serializable PostgreSQL importer
now registers/revives exact objects, writes version-1 pointers and explicit
present/absent audit entries atomically, returns the retained run on exact
retry, and re-reconciles mappings, pointers, objects, and audit after restart.
The guarded offline command is composed with three explicit operator
confirmations and repeats bundle
verification after Provider writes before entering the database transaction.
Its implementation is not real-provider acceptance evidence and does not
activate runtime handlers. The current C++/SQLite runtime remains the rollback
path.

## Verification

- migrate a clean database and validate restart/checksum behavior;
- prove manifest uniqueness, count reconciliation, ACCOUNT/ROOM target shape,
  group-kind foreign keys, present/absent shape, and target uniqueness;
- prove preview rejection occurs before provider writes;
- prove fresh import, exact retry after success, retry after a simulated
  post-upload failure, shared-object deduplication, missing mappings, pointer
  conflict rollback, and restart reconciliation against real PostgreSQL;
- retain provider PASS and no-unowned-object evidence before activation.
