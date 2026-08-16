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
- PostgreSQL write adapters enforce that rule inside the same transaction as
  the new write. They lock both accounts in stable UUID order before querying
  the bilateral block graph, so a committed block cannot be bypassed between a
  gateway/application precheck and insertion. This covers V2 submit/reply and
  forward destinations plus V1 direct messages, contact-request creation, and
  acceptance of a request that was pending before the block.
- An exact retry of a previously accepted message, forward, or same-direction
  pending contact request still returns its original result after a later
  block. It creates no new contact. All other denied paths reuse their existing
  generic authorization/invalid-target result rather than exposing block
  direction or existence.
- Blocking does not delete or mutate existing message history, revoke shared
  group membership, suppress group messages, or invalidate an already-issued
  short-lived attachment grant. Those require their own explicit policies.
- The block-mutation application service and wire surface remain detached. The
  durable write policy is nevertheless enforced by every current PostgreSQL
  direct-contact adapter, including V1 compatibility, so later composition
  cannot accidentally introduce an old-client bypass. This slice adds no block
  protocol message, gateway handler, or client UI.

## Consequences

The safety semantics and authenticated-actor boundary can evolve independently
from transport. Existing deployments have no block rows until a controlled
mutation surface is enabled, while direct-contact writes already fail closed if
such rows exist. Later expand-migrate-contract steps can add a default-off wire
capability and Web/Windows surfaces without changing storage enforcement.

## Verification

Application tests prove authenticated actor binding, stable operation identity,
self-block rejection before persistence, null containment, and fail-closed
correlation of adapter results. The disposable PostgreSQL gate proves clean V052
migration, same-database restart, exact retry, conflicting reuse, convergent
no-op, concurrent exact-operation convergence, opposite-direction lock ordering,
disabled-target denial, unblock, and the database self-edge constraint.
It also proves bilateral denial for V2 submit/reply/forward, V1 direct messages,
new and pending V1 contact requests, exact-retry preservation, group-message
non-effects, unblock behavior, and a deterministic block-commit/write race that
waits on the account-pair lock before rejecting the write. The real TLS/WSS
PostgreSQL gateway integration gate remains green with canonical DIRECT data.

## Rollback

Keep the forward migration and leave block mutation uncomposed. Disabling the
new policy code while retaining block rows would reopen direct-contact paths, so
rollback requires first disabling future mutations and proving the graph empty
or applying an explicitly approved data policy. If V052 must be physically
removed before product activation, restore the pre-migration database backup
rather than editing Flyway history. No protocol, runtime configuration, or
client state requires migration.
