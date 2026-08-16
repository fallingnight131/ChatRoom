# ADR-0408: V2 Account Blocking Foundation

- Status: Accepted
- Date: 2026-08-16
- Owners: project maintainers
- Related milestone: M6

## Context

Modern chat safety requires a user to stop unwanted direct contact. Treating
"block" as friend removal is insufficient: a removed account could send a new
contact request or direct message, retries could diverge, and presence or error
details could reveal private state. Blocking also must not rewrite durable
message history or silently change shared-group membership.

The first slice must establish server authority without activating an incomplete
wire or storage path.

## Decision

- A block is asymmetric durable account state owned by the Contacts module.
  Account A blocking B does not assert that B blocked A.
- The authenticated session supplies the actor account ID. The client supplies
  only a stable target account ID, desired boolean state, and canonical client
  operation ID. Self-block is rejected before persistence.
- Mutation is idempotent. Repeating one operation ID with the same actor, target,
  and desired state returns the original result; reusing it for different input
  is a conflict. Reapplying the already-current state succeeds with
  `changed=false`.
- V052 and the PostgreSQL adapter make the block state and operation result
  durable together. New operations lock both accounts in stable UUID order,
  require enabled accounts, and commit state plus result atomically. Durable
  truth does not live in Redis or gateway connection state.
- An active block in either direction denies new direct-message submissions and
  new contact requests between the pair. Denials use a generic unavailable
  result and do not disclose which side blocked. Unblocking does not restore a
  friendship, pending request, presence subscription, or deleted state.
- Blocking does not delete or mutate existing message history, revoke shared
  group membership, suppress group messages, or invalidate an already-issued
  short-lived attachment grant. Those require their own explicit policies.
- The Java application service and PostgreSQL adapter remain detached. This
  slice adds no protocol message, gateway handler, V1 behavior, or client UI.

## Consequences

The safety semantics and authenticated-actor boundary can evolve independently
from transport. Product behavior remains unchanged until later expand-migrate-
contract steps add pairwise query enforcement, a default-off wire capability,
and Web/Windows surfaces.

## Verification

Application tests prove authenticated actor binding, stable operation identity,
self-block rejection before persistence, null containment, and fail-closed
correlation of adapter results. The disposable PostgreSQL gate proves clean V052
migration, same-database restart, exact retry, conflicting reuse, convergent
no-op, concurrent exact-operation convergence, opposite-direction lock ordering,
disabled-target denial, unblock, and the database self-edge constraint.

## Rollback

Keep the forward migration and leave the adapter uncomposed. If V052 must be
physically removed before product activation, restore the pre-migration database
backup rather than editing Flyway history. No protocol, runtime configuration,
or client state requires migration.
