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

The first slices must establish server authority and then expose a reversible
server candidate without activating an incomplete client product path.

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
- Permanent V2 capability 7 and message types 128/129 define the additive block
  mutation command/result contract. The request contains target, desired state,
  and client operation UUID only; authentication supplies the actor. The
  gateway installs its handler only when exact
  `CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED=true` is supplied, and advertises
  capability 7 only when the client also requested it. The absent/default-false
  setting preserves the previous handshake and pipeline.
- The handler binds the actor from authenticated connection state, validates
  canonical payload identities, serializes at most eight pending mutations on
  the bounded messaging executor, and clears pending work at disconnect.
  Private target or policy denial maps to generic `NOT_AUTHORIZED`; operation-ID
  reuse maps to `IDEMPOTENCY_CONFLICT`; malformed and saturated paths remain
  distinct without disclosing account state.
- Fixed-cardinality telemetry distinguishes changed and convergent no-op
  mutations without account, target, or operation labels. The durable write
  policy remains enforced by every PostgreSQL direct-contact adapter, including
  V1 compatibility, so composition cannot introduce an old-client bypass.
- Default Web and Windows builds do not request capability 7. An independent
  Web V2 candidate built with exact `VITE_CHAT_V2_ACCOUNT_BLOCKING=true` requests
  it and exposes a localized, keyboard-contained direct-account dialog. It
  targets only the unique non-self member returned by the authorized DIRECT
  participant page, confirms either desired state, and preserves one operation
  UUID for explicit retry after disconnect. Ordinary Windows builds remain off;
  the independent candidate described below validates the authenticated actor
  and exact desired-state correlation before exposing its guarded product path.
  Server or either client activation alone therefore cannot create the complete
  product feature.
- An independent Windows CMake gate now carries capability 7 through immutable
  binary configuration, exact handshake validation, and an isolated bounded Qt
  transport route for types 128/129 and their correlated errors. It remains
  default-off; diagnostic schema 5 makes its absence or candidate presence
  observable without account or session data.
- A detached Windows ViewModel accepts only a complete actor-filtered
  participant projection with exactly one row for the active DIRECT
  conversation, labels initial state unknown, and keeps
  the operation UUID only for explicit same-desired-state retry. It has no
  database dependency.
- The enabled Windows device controller binds that protocol/ViewModel pair only
  from the server-established actor and session, sends through the isolated Qt
  route, and applies strictly correlated results. Disconnect clears protocol
  requests while retaining the same actor's explicit retry intent in page
  memory. Default composition constructs no account-block controller.
- The Windows conversation surface exposes a native modal only when the
  immutable gate produced a ViewModel and the authorized directory row is
  DIRECT. It waits for the complete actor-filtered participant projection,
  names the sole target, requires confirmation, disables duplicate pending
  actions, announces result/failure state, and keeps group/ordinary paths off.
- The Web candidate keeps the latest operation result in page memory only. It
  does not infer durable state before a result, persist a block graph, or expose
  a block-list read model. A fresh page labels state unknown and can issue an
  idempotent desired-state command.

## Consequences

The safety semantics and authenticated-actor boundary remain independent from
transport. Existing deployments have no block rows until the default-off server
candidate and an explicit compatible client are enabled, while direct-contact
writes already fail closed if such rows exist. Old clients omit capability 7
and keep their prior handshake bytes. The Web and Windows candidates are now
present; native Windows Release interaction, real endpoint canaries, and a future
block-list read model remain separate expand-migrate-contract steps.

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
Protocol golden-byte tests pin the new numeric registry, capability identity,
envelope kinds, and request field numbers. Focused handler tests prove
authenticated actor binding, capability and kind checks, canonical validation,
generic privacy denial, conflict mapping, executor saturation, and fixed
changed/no-op telemetry. The real TLS/WSS PostgreSQL gate negotiates capability
7 behind the exact server flag, applies a block, returns the identical exact
retry, verifies one durable row, and then denies a new direct message with the
generic result.
TypeScript protocol and application tests additionally prove the default-off
flag, strict actor/target/desired/operation correlation, authoritative unique
DIRECT target, disconnect failure containment, and same-operation retry.
Chromium and Firefox drive the generated-Protobuf fixture through focus entry,
localized confirmation, block, unblock, and trigger-focus restoration. This is
local browser-composition evidence, not a real gateway endpoint or release gate.
A same-revision Chromium flag-off build proves the action and type-128 command
are absent after client-first rollback.
The portable C++ binding gate additionally proves Windows request encoding,
self-block rejection, actor/target/desired-state/operation correlation,
same-operation explicit retry, and disconnect cleanup without claiming a native
Windows product build.
The Windows configuration policy rejects capability 7 without the preview; the
portable session/transport tests prove exact seven-capability negotiation,
default-independent capability selection, isolated command routing, correlated
response routing, and disconnect cleanup. Native Windows Release evidence is
still required before activation.
The ViewModel test proves incomplete pagination, duplicate/self-only identity,
target substitution, invented initial state, and disconnect cleanup fail
closed, while a same-desired-state retry preserves the operation UUID.
The composed controller test negotiates capability 7, binds the authenticated
actor, submits type 128 from an authoritative target projection, and routes a
correlated type 129 result. Configuration and composition guards keep ordinary
construction absent.
The native Windows CI gate builds the guarded dialog and verifies authoritative
target loading, accessible names, confirmation, pending disablement, correlated
status announcement, and group suppression. A macOS portability build is not
Windows product evidence; native Windows Release interaction and real-endpoint
activation/rollback evidence remain required.

## Rollback

Keep the forward migration and set
`CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED=false` (or remove it), then restart
or drain gateways so no connection retains a previously negotiated capability.
This stops new mutations but deliberately leaves existing block rows enforced.
Removing the direct-write policy while retaining rows would reopen contact
paths, so that deeper rollback requires first proving the graph empty or
applying an explicitly approved data policy. If V052 must be physically removed
before product activation, restore the pre-migration database backup rather
than editing Flyway history. Client state requires no migration because neither
candidate persists a block graph and ordinary product builds remain off.
