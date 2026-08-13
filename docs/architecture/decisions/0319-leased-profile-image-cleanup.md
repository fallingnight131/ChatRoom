# ADR-0319: Leased Profile Image Cleanup

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3

## Context

ADR-0318 records reference-aware cleanup intent for immutable profile-image
objects. Deleting immediately after reading that intent is unsafe: another
mutation can reuse the same content-addressed object between the database check
and provider deletion. Multiple cleanup workers can also race or crash after a
provider call.

## Decision

- Expand `profile_image_object` with nullable `delete_claim_id` and
  `delete_claimed_at`. Both fields are present or absent together.
- A bounded worker claims only cleanup requests older than a safety delay and
  only while no account/group pointer references the object. PostgreSQL assigns
  a unique claim token under row lock; stale claims may be reclaimed after a
  fixed lease.
- Pointer commits lock the same object row. An active, unconfirmed delete claim
  rejects that commit so the caller retries after cleanup. A confirmed old
  deletion may be revived only after the create-only provider write has produced
  fresh exact evidence; commit then clears cleanup/claim/confirmation state.
- Provider deletion is idempotent. Success is confirmed only with the exact
  claim token. Failure releases only that exact claim and retains cleanup intent.
- Keep the worker and S3 adapter inactive until the real-provider gate proves
  PUT/HEAD/GET/DELETE and checksum behavior.

## Consequences

The database never holds a transaction open across an object-provider call and
duplicate/stale workers cannot confirm each other's deletion. A worker paused
beyond its lease may race its replacement, so the lease must substantially
exceed the bounded provider timeout; token checks and idempotent delete make the
result recoverable. Content reuse during an active claim receives a retryable
failure rather than risking a referenced object deletion.

As of 2026-08-13, V041, the bounded application cleanup pass, and the PostgreSQL
claim/release/confirm adapter are implemented and exercised against a clean,
restarted real PostgreSQL database. The strict-key, idempotent S3 deletion
adapter is also implemented under unit tests. Runtime worker composition and
real-provider evidence remain inactive gates.

## Verification

- migrate V040 data forward and validate the claim-pair constraint;
- test claim, exact release, exact confirmation, stale reclaim, active-reference
  exclusion, pointer-commit exclusion, and confirmed-object revival in real
  PostgreSQL;
- test provider success/failure and worker restart with bounded batches.
