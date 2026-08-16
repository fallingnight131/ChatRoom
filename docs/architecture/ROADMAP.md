# Evolution Roadmap

## How to Use This Roadmap

Execute milestones in order, but deliver each as small vertical slices. A
milestone is complete only when its exit criteria are met. Revisit sequencing
through an ADR when evidence changes.

Do not begin a later distributed-systems milestone merely because its technology
is attractive. Reliability and client responsiveness come before service count.

## M0 — Architecture and Delivery Baseline

Goal: make the current system measurable and safe to change.

Status: **repository scope complete on 2026-07-11**. See the stored
[`M0 acceptance record`](../baselines/M0_ACCEPTANCE_2026-07-11.md). Native
Windows/macOS jobs execute after these local commits are pushed; their first run
is required operational evidence but does not add more M0 product scope.
The macOS lane is retained as historical/development evidence only after the
Web-and-Windows support decision in
[`ADR-0009`](decisions/0009-web-and-windows-product-scope.md).

Progress:

- [x] Establish repository governance, target architecture, project skills, and
  ADR process.
- [x] Capture code-derived current-system, V1 protocol, and SQLite inventory
  baselines.
- [x] Establish inventory, web, SQLite schema, and native Qt product Release
  verification in CI.
- [x] Add a clean/restart SQLite schema consistency regression test.
- [x] Add real V1 TCP smoke coverage for login, room chat, history, file metadata,
  reconnect, and recall.
- [x] Extend V1 smoke coverage to friend request/direct-message flows.
- [x] Add critical V1 integration smoke tests.
- [x] Pin the desktop support/build matrix and automate unsigned Windows/macOS
  verification artifacts without conflating them with M4 installers.
- [x] Record the first reproducible V1 performance baseline, including message
  latency, SQLite latency, CPU/RSS, connection count, throughput, and available
  artifact sizes.

Work:

- adopt `AGENTS.md`, project skills, target architecture, and ADR process;
- reconcile `README.md`, legacy design material, protocol names, and actual
  implementation;
- document the current V1 JSON protocol and database schema from code;
- establish reproducible Qt server, Qt client, and web builds;
- add a small integration harness for login, room chat, direct chat, reconnect,
  recall, and file metadata;
- record baseline CPU, memory, connection count, message latency, SQLite latency,
  and package sizes;
- establish Windows release build automation.

Exit criteria:

- [x] a clean checkout has one documented verifier and pinned native build
  workflows;
- [x] critical V1 flows have automated smoke coverage;
- [x] architecture-changing work requires an ADR;
- [x] performance claims refer to a stored test scenario and result.

## M1 — Secure and Reliable V1

Goal: correct the highest-risk behavior before migration.

Status: **complete on 2026-08-11** within the documented V1 compatibility
boundary. See the stored
[`M1 acceptance record`](../baselines/M1_ACCEPTANCE_2026-08-11.md). Durable
client repositories, delivery/read acknowledgements, multi-device semantics,
and non-message state synchronization remain explicitly assigned to M2/M6.

Progress:

- [x] Stop persisting plaintext Web login passwords; retain credentials only in
  current-page memory for V1 disconnect reauthentication and purge legacy
  browser session keys.
- [x] Introduce libsodium Argon2id hashing for new/changed passwords and upgrade
  legacy salted SHA-256 rows after successful login.
- [x] Hash room passwords with Argon2id, upgrade legacy plaintext after a
  successful join, stop returning secrets, and honor protected-room creation.
- [x] Enforce server-side membership/permission checks across room/direct
  message, administration, read-state, upload, and file-download operations.
- [x] Bound TCP/WebSocket frames, malformed envelopes, per-connection message
  bursts, and slow-consumer pending bytes.
- [x] Add password/message/history/file/chunk field limits and per-connection
  expensive-authentication throttling.
- [x] Add bounded single-node gateway/account/direct-peer-IP authentication
  controls with structured denial monitoring; proxy-aware/distributed
  enforcement remains a later gateway concern.
- [x] Add critical SQLite indexes and lock their hot query shapes with
  `EXPLAIN QUERY PLAN` regression tests.
- [x] Add V1-compatible idempotency, durable acceptance, stable per-room
  sequences, and bounded reconnect resume for room text/emoji messages.
- [x] Extend idempotent submission, stable per-friendship sequences, and bounded
  reconnect resume to direct text/emoji messages.
- [x] Move Web and Windows composer room/friend attachment bytes from
  JSON/Base64 to the authorized streaming HTTP upload bridge.
- [x] Move normal Windows room/friend attachment downloads to the authorized
  streaming HTTP data plane while retaining old-server fallback.
- [x] Move upgraded Windows multi-target attachment forwarding to an
  authorization-checked server command; old-server fallbacks and legacy-path
  retirement remain.
- [x] Publish the already-durable room/friend sequence and authoritative
  timestamp on every live attachment notification.
- [x] Make upgraded Web/Windows room and friend upload finalization idempotent
  with durable `clientMessageId`, explicit acceptance, conflict detection, and
  restart-safe retry.
- [x] Reconcile repeated stable message IDs from history as authoritative state
  updates in Web and Windows instead of discarding them as duplicates.
- [x] Expand the V1 room/direct schema with indexed mutation cursors for
  replayable recall, without changing current wire behavior yet.
- [x] Make room/direct recall idempotent and replayable through the conversation
  sequence cursor, including reconnect and process-restart recovery.
- [x] Add stable authorization/rejection/persistence error codes to room and
  direct recall outcomes.
- [x] Expand the V1 schema with an indexed, idempotency-keyed administrative
  deletion event table without changing current deletion behavior yet.
- [x] Make administrative message/file deletion idempotent and replayable on
  the room cursor, and reconcile its events in Web and Windows clients.
- [x] Make the Web client resume the active room from its in-memory cursor after
  reconnect login, with ordered mixed message/event pagination.
- [x] Make the Windows client reauthenticate from process memory before
  publishing reconnect success, then resume the active room cursor in bounded
  mixed message/event pages.
- [x] Resume the active direct conversation from its in-memory cursor after Web
  or Windows reconnect authentication, including bounded paging and replayed
  recall state.
- [x] Bound M1 reliable-message semantics to durable acceptance/idempotency,
  conversation sequencing, replayable recall/deletion, and active-conversation
  reconnect; route durable outboxes/cache, delivery/read acknowledgement,
  multi-device behavior, and other state synchronization to M2/M6.

Work:

- stop storing plaintext login passwords in browser storage;
- introduce modern password hashing with gradual hash upgrade;
- validate room/friend membership for every server message and file action;
- add inbound size limits, rate limits, and outbound slow-consumer limits;
- remove Base64 file transfer from normal messaging by adding direct object
  storage upload/download authorization;
- add `clientMessageId` and idempotent send handling to a compatible V1 extension;
- define server message IDs and stable sequence/cursor behavior;
- add reconnect tests and database indexes validated by query plans.

Exit criteria:

- [x] retries do not create duplicate messages;
- [x] reconnect recovers a documented range of missing messages;
- [x] normal supported-client attachment bytes bypass JSON/Base64 messaging and
  use the authorized HTTP data plane;
- [x] authentication, authorization, transport, and file threat models have
  regression tests.

## M2 — Client Data and Experience Foundation

Goal: make clients fast and offline-tolerant before major UI expansion.

Status: **complete on 2026-08-11** for the supported Web and Windows clients.
See the stored
[`M2 acceptance record`](../baselines/M2_ACCEPTANCE_2026-08-11.md). Native
Windows release evidence remains an M4 gate; device-aware delivery and read
aggregation remain M6 work.

Progress:

- [x] Add account-partitioned, 500-message-bounded Web IndexedDB room/direct
  snapshots and sequence cursors; hydrate on selection, synchronize forward,
  and evict snapshots when room or friendship access is lost.
- [x] Persist bounded Web room/direct text drafts with debounced writes,
  conversation-switch/unmount flush, and send-time clearing.
- [x] Add a durable Web text/emoji optimistic outbox with stable-key reconnect
  retry, accepted/failed presentation, and explicit same-key manual retry.
- [x] Extract synchronization/outbox orchestration from the large chat store and
  extend optimistic behavior to restartable attachments.
  - [x] Move room/direct cache, cursor, sync-request, optimistic command, retry,
    and ACK policy into a transport/cache-injected Web conversation coordinator.
  - [x] Add a restartable Web attachment command model that handles browser file
    handle permissions, source revision, fresh authorization, and cleanup.
    - [x] Add the IndexedDB-v3 account/conversation-scoped command store with
      source revision, optional file handle, and no persisted authorization/bytes.
    - [x] Add transport-neutral permission/source recovery, exact-revision
      reselection, cancellation, failure, and revoked-conversation cleanup policy.
    - [x] Integrate user-visible recovery controls and serialized
      fresh-authorized dispatch with the existing upload adapter.
- [x] Establish the Windows SQLite conversation repository with versioned
  schema, account isolation, bounded message metadata, cursor/draft fields,
  pruning, restart tests, and a newer-schema refusal gate.
- [x] Render cached Windows room conversations before network history, persist
  room message/mutation state and cursor high watermarks, and evict local room
  data after membership access is lost.
- [x] Apply the same durable cached-render and incremental reconciliation to
  Windows direct conversations, including relationship eviction and safe
  account/peer rename migration.
- [x] Persist bounded Windows room/direct drafts with debounced writes,
  switch/close flush, relationship eviction, and send-time clearing.
- [x] Add a durable Windows text/emoji optimistic outbox with stable-key
  reconnect retry, accepted/failed presentation, and explicit same-key retry.
- [x] Replace Windows live-message full-snapshot rewrites with transactional
  single-message upserts while retaining bounded cache pruning.
- [x] Extract Windows text/emoji outbox orchestration from `ChatWindow` into a
  transport-neutral, repository-backed application service with independent
  restart, authorization-gate, acceptance, failure, and retry tests.
- [x] Extract Windows room/direct snapshot persistence, monotonic cursor state,
  key promotion, eviction, and cache reset into a transport-neutral
  conversation synchronization service.
- [x] Move Windows V1 room/direct history parsing, malformed/rejected response
  handling, and progress-guarded continuation scheduling out of `ChatWindow`.
- [x] Extend Windows durable pending behavior to restartable attachments.
  - [x] Add the schema-v2 account/conversation-scoped attachment command store;
    do not persist ephemeral upload authorization or claim byte-range resume.
  - [x] Add source-revision validation, membership-gated recovery, stale-state
    normalization, and completion/cancellation cleanup to a transport-neutral
    Windows application service.
  - [x] Add serialized V1 dispatch, reconnect reset, fresh authorization, and
    ACK/notification/cancellation cleanup to the Windows adapter layer.
  - [x] Add user-visible failed-task retry, source reselection, and cancellation
    controls to the Windows UI.
- [x] Extend the Windows cache control to clear account-isolated, server-
  recoverable SQLite history while preserving drafts and unresolved sends.
- [x] Add current-scope optimistic states, virtualization, bounded media-cache
  policy, and accessibility foundations.
  - [x] Establish Web live-log/composer semantics, keyboard message/file actions,
    visible focus, reduced-motion behavior, and an accessibility verification policy.
  - [x] Add measured variable-height Web message-list virtualization with
    overscan, history-prepend anchoring, and bottom-follow behavior.
  - [x] Enforce a zero-media-byte Web IndexedDB policy, including version-2
    cleanup of legacy Base64 thumbnails, media payloads, and temporary authorization.
  - [x] Bound the Qt model/view timeline to 500 resolved messages while
    preserving unresolved user sends across append, sync, history, and ACK paths.
  - [x] Present authoritative server acceptance as `已发送` on Web and Windows
    without mislabelling it as delivered or read.
  - [x] Add durable private-chat read watermarks and receipt presentation;
    multi-device aggregation remains M6 scope.
    - [x] Publish and persist the authorized V1 peer read watermark.
    - [x] Consume monotonic live/recovered watermarks in the Web client.
    - [x] Consume monotonic live/recovered watermarks in the Windows client.

Work:

- extract Windows Qt transport, application services, sync engine, and local
  repository from the large window/controller;
- add desktop SQLite for messages, conversations, drafts, cursors, and pending
  sends;
- split the web chat store and add IndexedDB-backed repositories;
- render cached conversations immediately and synchronize in the background;
- add optimistic send states, retry, failure, durable-acceptance, and private
  read presentation; reserve device-aware delivery for M6/V2;
- add virtualized history and bounded media caches;
- define shared semantic design tokens and accessibility expectations.

Exit criteria:

- [x] opening a recent conversation does not require a network round trip;
- [x] clients reconcile optimistic and server messages without duplicates;
- [x] restarting offline preserves drafts and pending messages;
- [x] long histories do not grow view memory linearly.

## M3 — Java V2 Modular Backend

Goal: introduce the target Java platform without a big-bang cutover.

Progress:

- [x] Establish the checksum-pinned Gradle 8.14.3/JDK 21 multi-module workspace,
  inward dependency boundaries, warning-clean tests, and an independent CI gate.
- [x] Implement the V2 envelope and generated Java/C++/TypeScript schemas.
  - [x] Define the versioned Protobuf envelope, generated Java binding,
    structural validation, and golden wire test.
  - [x] Generate C++ and TypeScript bindings from the same source and prove
    Java/TypeScript golden-wire compatibility.
  - [x] Compile the generated C++ binding with the pinned runtime and parse the
    same golden envelope before declaring cross-language compatibility complete.
- [x] Build the Netty gateway and modular application core.
  - [x] Add the bounded binary WebSocket frame aggregation, Protobuf decoding,
    envelope validation, and negative-path embedded-channel tests.
  - [x] Define the permanent V2 control message registry, bounded Web/Windows
    client hello, version negotiation semantics, and three-language golden test.
  - [x] Add the single-use bounded ClientHello state machine and safe negotiation
    errors without enabling a listener.
  - [x] Define bounded generated authentication/session payloads, fixed token
    length, non-enumerating rejection semantics, and three-language wire tests.
  - [x] Add a fresh-login gateway state machine with off-event-loop application
    dispatch, server-bound connection identity, generic rejection, secret
    cleanup, and session-spoofing denial without enabling a listener.
  - [x] Encode outbound envelopes as bounded binary WebSocket messages and map
    malformed/oversized frames to fixed safe 1002/1009 close outcomes.
  - [x] Add gateway-owned fixed authentication workers, a bounded queue,
    deterministic saturation shedding, lifecycle shutdown, and non-secret
    saturation signals.
  - [x] Add independent handshake/authentication deadlines with deterministic
    1008 close outcomes and timer cancellation at phase changes/disconnect.
  - [x] Add bounded process-local account/direct-peer/gateway authentication
    windows before password copies or worker admission, with generic denial and
    non-identifying counters.
  - [x] Add fixed-label authentication counters, execution-duration buckets,
    upgrade-pending visibility, and power-of-two sampled safe warning logs.
  - [x] Add an exact-path, GET-only, loopback management server with explicit
    readiness, bounded workers, and fixed-label Prometheus rendering.
  - [x] Define bounded direct/trusted-CIDR peer resolution with right-to-left
    sanitized forwarding-chain parsing and fail-closed proxy errors.
  - [x] Enforce that policy in a reusable pre-WebSocket HTTP handler and pass
    only the frozen canonical address into authentication admission.
  - [x] Separate exact Web/Windows upgrade paths, enforce an HTTPS Web Origin
    allowlist, and bind the endpoint platform to `ClientHello`.
  - [x] Require one exact configured TLS Host authority before either product
    endpoint can upgrade.
  - [x] Require the fixed `chat.v2` WebSocket subprotocol and compose the bounded
    frame, phase, negotiation, authentication, and authenticated-idle handlers
    in one deterministic post-upgrade pipeline.
  - [x] Add a configurable authenticated writer-idle Ping interval below the
    reader-idle timeout so browser automatic Pong traffic keeps healthy V2
    connections alive while silent peers still close deterministically.
  - [x] Centralize fail-before-bind environment validation for TLS material,
    PostgreSQL secrets, endpoints, proxy mode, workers, queues, timeouts, and
    abuse-control bounds.
  - [x] Compose an owned WSS listener with mandatory TLS, bounded HTTP/WebSocket
    parsing, connection cap, write watermarks, upgrade deadline, ordered policy
    handlers, real loopback upgrade tests, and deterministic shutdown without
    activating `GatewayMain`.
  - [x] Add a bounded fail-fast PostgreSQL connection-pool boundary with remote
    `verify-full` TLS enforcement and an explicit numeric-loopback-only local
    development exception.
  - [x] Compose PostgreSQL, identity cryptography, authentication/resume,
    bounded workers, trusted-proxy/HTTP/WSS policy, loopback metrics/readiness,
    and reverse-order shutdown in the independently runnable `GatewayMain`.
  - [x] Route durable V2 conversation/message commands through this gateway
    before any product traffic cutover; coordinate distributed limits through
    Redis in M5.
    - [x] Permanently allocate bounded submit/accepted/history/page Protobuf
      payloads and prove Java/C++/TypeScript golden-wire compatibility.
    - [x] Dispatch authenticated UTF-8 text submission and sequence-history
      reads through the durable PostgreSQL adapter with server-bound identity,
      per-connection ordering, bounded off-event-loop work, and safe errors.
    - [x] Isolate message database work from password/session work with an
      independently configured bounded worker pool and reverse-order ownership.
    - [x] Export fixed-cardinality message outcome counters and bounded-worker
      pressure gauges through the loopback metrics endpoint.
    - [x] Permanently allocate the bounded composite-cursor V2 conversation
      directory request/page and prove Java/C++/TypeScript compatibility.
    - [x] Dispatch the authenticated directory through the PostgreSQL adapter in
      the connection-local serial command queue with safe failures and telemetry.
    - [x] Publish the authoritative generated V2 TypeScript bindings into the Web
      source tree and fail compatibility verification when committed output is stale.
    - [x] Add an unconnected Web V2 TypeScript protocol/session state machine with
      bounded frames/requests, strict response correlation, ephemeral credentials,
      server-session validation, and negative-path tests while V1 remains live.
    - [x] Add an unconnected Web V2 WebSocket lifecycle adapter for the exact WSS
      route/subprotocol with phase deadlines, binary-only delivery, cancellable
      jittered reconnect, fresh per-connection protocol state, and transport tests.
    - [x] Add explicit Web V2 session-resume command support with one-use proof
      copying/clearing and rotated-session validation, without persistence or
      automatic replay.
    - [x] Add a rollback-isolated, account/conversation-partitioned Web V2
      IndexedDB snapshot store with bounded text metadata and exact decimal
      sequence cursors, without changing the live V1 database version.
    - [x] Add a transport/cache-injected Web V2 application coordinator for
      cached-first directory/history use cases, bounded paging, optimistic text,
      correlated ACK/error handling, exact cursors, and at-least-once deduplication.
    - [x] Add memory-only automatic Web session resume with proof rotation,
      observer redaction, rejection/stop cleanup, and same-session cursor recovery.
    - [x] Recover cached Web V2 sending commands only after authoritative history,
      serialize same-key replay one-at-a-time, stop visibly on error, and bound
      local retention to 500 accepted plus 100 unresolved messages.
    - [x] Compose the Web V2 stack behind an exact default-off build flag, strict
      WSS/app-version configuration, lazy chunk boundary, non-secret device
      identity, and deterministic page-exit cleanup without starting traffic.
    - [x] Add a build-gated V2 preview route that explicitly owns connection and
      transient authentication, then renders directory, cache-first history,
      optimistic text, acceptance, and retry without replacing V1 screens.
    - [x] Pause Web V2 socket/retry work while the browser is offline, reconnect
      immediately on network recovery, and preserve only the rotated in-memory
      resume proof across that transition.
    - [x] Permanently allocate the authenticated V2 live-message event and make
      Web reconciliation advance only contiguous sequence or repair gaps through
      history before enabling gateway fan-out.
    - [x] Publish new durable messages to authorization-established active
      conversations on one gateway with race-free history subscription,
      duplicate suppression, bounded route cleanup, and slow-consumer closure.
- [x] Establish the forward-only PostgreSQL migration module and V2 core schema
  with real clean/restart, sequence, idempotency, and constraint verification.
- [ ] Implement persistence repositories and a verified one-way V1 import before
  PostgreSQL can become authoritative for any traffic slice.
  - [x] Add deterministic V1 numeric-user-ID to V2 UUID mapping, two-generation
    credential validation, safe issue reporting, and source fingerprinting
    before any target comparison or write.
  - [x] Persist the otherwise non-invertible V1 numeric-user-ID to account UUID
    compatibility projection atomically with verified identity import, without
    polluting the V2 account domain.
  - [x] Expose the mapping through an isolated read-only V1 compatibility port
    with exact enabled-account PostgreSQL lookup in both directions.
  - [x] Add transport-independent V1 login eligibility and result projection so
    unmapped V2-native accounts cannot receive V1 sessions and resume secrets
    never cross the compatibility boundary.
  - [x] Add a bounded, duplicate-detecting streaming JSON codec for the inactive
    V1 login slice with compatible success/generic rejection output and no V2
    session-secret exposure.
  - [x] Add an inactive V1 Web login handler with bounded-worker dispatch,
    shared admission control, server-bound identity, generic failure, and
    late-result suppression without opening a partial product route.
  - [x] Compose that inactive handler from real PostgreSQL identity/session and
    V1 projection adapters plus compatible cryptography, and verify imported
    acceptance versus unmapped V2-native rejection on disposable PostgreSQL.
  - [x] Preserve V1 single-account connection semantics in the detached Java
    module with atomic replacement, bounded `FORCE_OFFLINE`, and stale-close-safe
    process-local cleanup.
  - [x] Compose client-driven V1 heartbeat acknowledgement and authenticated
    reader-idle closure into the detached handler pipeline without consuming
    downstream business frames.
  - [x] Bound the detached V1 post-upgrade authentication phase with a fixed
    policy close, explicit success cancellation, and late-result suppression.
  - [x] Reserve an inactive exact `/v1/web` + `chat.v1` browser upgrade guard
    using the shared HTTPS Origin allowlist, without crossing V1/V2 routes.
  - [x] Add a detached bounded V1 WebSocket upgrade adapter that installs the
    complete compatibility application pipeline only after the guard-approved
    exact path and negotiated subprotocol are independently confirmed.
  - [x] Add a transport-independent V1 room-directory projection over the
    canonical authorized directory with bounded batch legacy-ID translation,
    authoritative unread counts, and fail-closed partial-list prevention.
  - [x] Add a detached strict V1 room-list JSON/Netty adapter with server-bound
    identity, off-loop bounded execution, no partial failure response, and fixed
    completion/failure/saturation telemetry before runtime composition.
  - [x] Compose login, heartbeat, and room listing in the detached Java V1 module
    and verify imported membership, unread/admin projection, isolation, and no
    canonical-ID exposure against disposable PostgreSQL without activating a route.
  - [x] Add the forward-only canonical contact-request lifecycle and isolated
    V1 numeric request-ID mapping before importing or serving friend state.
  - [x] Add deterministic pending-only V1 contact-request planning, WAL-aware
    query-only extraction, and exact protected-backup input reconciliation.
  - [x] Add constrained contact-request import audit plus strict PostgreSQL
    preview/apply, exact reconciliation, conflict rollback, and idempotent rerun.
  - [x] Add offline contact preview/final-verify/apply commands with explicit
    fingerprint confirmation, safe output, and maintenance-window guidance.
  - [x] Preserve V1 self-chat friendships as one-member DIRECT conversations
    instead of blocking sources after the legacy friend-list side effect.
  - [x] Add a bounded transport-independent V1 friend-directory projection with
    exact legacy IDs, unread/read state, pending count, presence, and fail-closed
    partial-list prevention.
  - [x] Implement its repeatable-read PostgreSQL snapshot and batch account
    projection with exact message unread and V1 peer-read translation.
  - [x] Add and compose the detached strict V1 friend-list JSON/Netty adapter,
    process-local presence, fixed telemetry, and login-to-list PostgreSQL test.
  - [x] Add the bounded transport-independent V1 pending-friend-request list
    with exact action identifiers and duplicate-result rejection.
  - [x] Implement its repeatable-read PostgreSQL projection with canonical-count
    reconciliation and fail-closed missing request/account mappings.
  - [x] Add and compose the detached strict V1 pending-request JSON/Netty adapter
    with fixed telemetry and login-to-pending PostgreSQL verification.
  - [x] Define recipient-bound, retry-idempotent V1 friend-request rejection as
    a transport-independent application boundary.
  - [x] Implement its serialized PostgreSQL row-lock transition with enabled-
    recipient authorization, database time, and exact-retry idempotency.
  - [x] Add and compose the detached strict V1 friend-request rejection handler
    with bounded execution, fixed telemetry, and end-to-end PostgreSQL refresh.
  - [x] Define recipient-bound V1 friend-request acceptance with atomic DIRECT
    establishment, exact-retry success, and first-apply-only notification intent.
  - [x] Implement atomic PostgreSQL acceptance with canonical DIRECT reuse,
    active memberships, descending runtime V1 IDs, and full retry validation.
  - [x] Add and compose detached strict V1 acceptance plus first-apply online
    notification with authoritative routing and real PostgreSQL dual-login proof.
  - [x] Define a bounded transport-independent V1 user-search projection with
    server-bound self exclusion, literal keywords, presence, and legacy-only IDs.
  - [x] Implement its read-only PostgreSQL join with enabled/mapped filtering,
    deterministic order, literal wildcard escaping, and database result bounds.
  - [x] Add and compose detached strict V1 user search with fixed telemetry and
    real PostgreSQL offline-to-online presence verification.
  - [x] Define server-resolved V1 friend-request creation with typed denial,
    same-direction retry success, and first-apply-only notification intent.
  - [x] Implement serialized PostgreSQL request creation with concurrent outcome
    convergence, active-friend/reverse checks, and descending runtime V1 IDs.
  - [x] Compose detached strict V1 friend-request creation with compatible typed
    errors, fixed telemetry, authoritative first notification, and retry suppression.
  - [x] Prove the composed friend-request creation/notification path with two
    real imported logins on disposable PostgreSQL.
  - [x] Define V1 friend removal as an idempotent, server-authorized termination
    of active DIRECT memberships that preserves durable history and mapping.
  - [x] Implement atomic PostgreSQL friend removal with retained conversation
    state, exact-retry detection, and fail-closed partial-membership handling.
  - [x] Compose detached strict V1 friend removal with compatible responses,
    fixed telemetry, authoritative first notification, and real PostgreSQL proof.
  - [x] Define server-bound V1 direct text/emoji submission with atomic future
    canonical/V1 identity, retry semantics, and first-accept-only fan-out intent.
  - [x] Add a descending signed-32-bit runtime V1 friend-message ID allocator
    while retaining imported IDs and requiring transactional collision checks.
  - [x] Implement atomic PostgreSQL V1 direct text/emoji submission with active
    relationship authorization, canonical/V1 mapping, and exact retry recovery.
  - [x] Compose detached V1 direct messaging with authoritative ACK/live fields,
    first-only local fan-out, fixed telemetry, and real PostgreSQL dual-login proof.
  - [x] Define a bounded UUID-free V1 direct-history projection that folds recall
    entries into strict creation/mutation sequence pages for reconnect recovery.
  - [x] Preserve V1 text/emoji presentation in the isolated message mapping and
    backfill pre-cutover nulls only through verified source import.
  - [x] Implement repeatable-read PostgreSQL V1 direct history with complete
    mapping checks, recall folding, bounded paging, and gap-safe cursor advance.
  - [x] Compose detached strict V1 direct history with bounded off-loop reads,
    compatible response fields, fixed telemetry, and real reconnect recovery.
  - [x] Define owner-only V1 direct recall with a database-time 120-second
    first-apply window, atomic mutation sequence, and notification-safe retry.
  - [x] Implement retry-convergent PostgreSQL V1 direct recall with sender and
    active-relationship checks plus database-enforced one-event integrity.
  - [x] Compose detached strict V1 direct recall with authenticated ownership,
    first-only local notification, duplicate suppression, and fixed telemetry.
  - [x] Prove V1 direct send, replacement login, recall, duplicate suppression,
    and mutation-sequence history recovery against disposable PostgreSQL.
  - [x] Define authenticated V1 room text/emoji submission with atomic future
    canonical/V1 identity, retry semantics, and first-accept broadcast intent.
  - [x] Implement retry-convergent PostgreSQL V1 room submission with active
    membership/device checks and atomic canonical/V1 mapped message identity.
  - [x] Compose detached strict V1 room messaging with durable ACK-first sender
    echo, authoritative batch membership filtering, first-only process-local
    fan-out, duplicate suppression, fixed telemetry, and real PostgreSQL proof.
  - [x] Define a bounded UUID-free V1 room-history projection that merges
    message creation, recall mutation, and administrative deletion under one
    reconnect cursor without changing latest-page compatibility.
  - [x] Implement repeatable-read PostgreSQL V1 room history with complete
    mapping checks, mixed message/recall/deletion pagination, and gap-safe
    cursor advancement.
  - [x] Compose detached strict V1 room history with bounded off-loop mixed-page
    reads, compatible response fields, fixed telemetry, and real reconnect
    recovery after replacement login.
  - [x] Define owner-only V1 room recall with server-bound room/message identity,
    a database-time 120-second first-apply window, atomic mutation sequence,
    and notification-safe exact retry intent.
  - [x] Implement retry-convergent PostgreSQL V1 room recall with mapped resource,
    enabled-account, active-membership, sender, and database-time checks plus
    database-enforced one-event integrity.
  - [x] Compose detached strict V1 room recall with authenticated ownership,
    authoritative first-only local fan-out, duplicate suppression, fixed
    telemetry, and replacement-login history recovery.
  - [x] Define server-authorized V1 room-read cursor advancement using monotonic
    canonical conversation sequences rather than legacy database message IDs.
  - [x] Implement serializable PostgreSQL V1 room-read advancement with exact
    active-member locking, high-watermark observation, one-account update, and
    repeat-idempotent monotonic results.
  - [x] Compose detached response-free V1 room-read handling with authenticated
    identity, bounded off-loop execution, fixed telemetry, and real PostgreSQL
    unread-directory reconciliation.
  - [x] Define server-authorized V1 private read-cursor advancement with mapped
    peer notification intent and sequence-ordered V1 last-read message identity.
  - [x] Implement serializable PostgreSQL V1 private read advancement with exact
    participant locking, canonical high-watermark movement, and sequence-ordered
    V1 message-ID recovery in both live results and the friend directory.
  - [x] Compose detached response-free V1 private read handling with authenticated
    identity, mapped-peer notification, repeat convergence, fixed telemetry, and
    real replacement-login recovery from PostgreSQL.
  - [x] Define bounded authenticated V1 room search with exact legacy identities,
    active-member counts, deterministic projection intent, and no UUID exposure.
  - [x] Implement repeatable-read PostgreSQL V1 room search with exact numeric ID
    lookup, escaped literal titles, active-member counts, and complete creator
    mapping enforcement.
  - [x] Compose detached strict V1 room search with authenticated identity,
    bounded off-loop execution, compatible UUID-free output, fixed telemetry,
    and real PostgreSQL login-to-search proof.
  - [x] Define idempotent V1 room creation with server-bound ownership, bounded
    request/title/password intent, secret zeroing, pre-persistence hashing, and
    atomic GROUP/OWNER/ROOM-mapping requirements.
  - [x] Separate salted room-password verification hashes from dedicated server-
    keyed stable idempotency tags before designing protected-room retry storage.
  - [x] Add V023 and serializable PostgreSQL V1 room creation with atomic GROUP,
    OWNER, optional Argon2id credential, ROOM mapping, idempotency record, and
    collision-safe descending runtime room ID.
  - [x] Implement the room-password crypto port with salted compatible Argon2id,
    fixed-domain HMAC-SHA-256 retry tags, strict 32-byte key ownership, and
    deterministic secret cleanup.
  - [x] Add mandatory canonical 32-byte secret-manager input for detached V1
    protected-room retry tags, with no default and close-zeroed lifecycle.
  - [x] Compose detached strict V1 room creation with envelope idempotency,
    server-bound creator, zeroed password copies, compatible responses, fixed
    telemetry, and real protected-room PostgreSQL/relogin proof.
  - [x] Define authenticated V1 room joining with idempotent existing
    membership, owned password bytes, credential verification, and an exact
    access snapshot that persistence must compare during atomic admission.
  - [x] Implement atomic PostgreSQL V1 room joining with enabled-account,
    GROUP/ROOM mapping, credential-snapshot, active-membership, and bounded
    capacity enforcement.
  - [x] Import and reconcile custom V1 `room_settings.max_members` into the
    GROUP admission policy before activating Java room joining; default 50 is
    valid only when the verified source has no explicit override.
  - [x] Compose detached strict V1 room joining with password-attempt admission,
    compatible responses and first-join notification intent, fixed telemetry,
    and real PostgreSQL/relogin proof.
  - [x] Define authenticated idempotent V1 room leaving with atomic deterministic
    ownership succession and durable last-member dissolution semantics.
  - [x] Add explicit GROUP lifecycle state and atomic PostgreSQL V1 room leaving,
    then exclude dissolved rooms from every room authorization/projection path.
  - [x] Compose detached strict V1 room leaving with first-only presence and
    ownership notifications plus real PostgreSQL/relogin proof.
  - [x] Define bounded authorized V1 room-member listing with complete mapped
    identities, canonical roles, and process-local presence projection.
  - [x] Implement active-lifecycle PostgreSQL member listing with complete mapped
    projection and real PostgreSQL authorization proof.
  - [x] Compose strict `USER_LIST_REQ` handling with bounded compatible output,
    fixed telemetry, and real PostgreSQL login-to-presence proof.
  - [x] Define a server-authorized read-only V1 room-settings contract with
    complete durable limits, strict value invariants, and no synthesized data.
  - [x] Add canonical GROUP resource policy, import and exactly reconcile all
    four verified V1 room limits, and authorize complete PostgreSQL reads.
  - [x] Compose strict read-only `ROOM_SETTINGS_REQ` handling with compatible
    output, fixed telemetry, and real PostgreSQL login-to-settings proof.
  - [x] Add a typed, one-to-one V1 room/friendship to V2 conversation mapping
    projection with database-enforced source namespaces and target kinds.
  - [x] Expose V1 conversation mappings through a typed read-only application
    port and exact PostgreSQL lookup in both translation directions.
  - [x] Add a pure deterministic V1 room/friendship pre-write planner with role
    projection, retained read pointers, and blocking graph validation.
  - [x] Add WAL-aware query-only SQLite extraction for the current V1 room,
    membership, administrator, friendship, and read-pointer graph.
  - [x] Reconcile the V1 conversation graph against the same physically verified
    whole-file backup with a separate re-verifiable import capability.
  - [x] Add an append-only V1 conversation-import audit with database-enforced
    source/result reconciliation and whole-file backup proof fields.
  - [x] Add strict PostgreSQL conversation preview/apply with serializable
    insertion, exact post-write/source reconciliation, idempotent rerun, and
    membership/mapping conflict rollback.
  - [x] Add offline conversation preview/final-verify/apply commands with an
    independent explicit graph fingerprint and a maintenance-window runbook.
  - [x] Add deterministic V1 shared-sequence/read-cursor planning, query-only
    same-snapshot SQLite extraction, and exact protected-backup reconciliation.
  - [x] Add the V2 conversation-entry foundation for message creation, recall,
    and deletion sequences, including non-authenticating legacy provenance.
  - [x] Add database-enforced typed V1 retained-message and deletion-event
    compatibility maps plus exact read-only message identity projection.
  - [x] Import verified V1 message payloads, recall/deletion entries, translated
    read cursors, and preserved high watermarks atomically before message cutover.
  - [x] Add explicit offline message preview/final-verify/apply commands with
    dual fingerprint confirmation, safe output, runbook, and real-PostgreSQL gate.
  - [x] Add an inactive, transport-independent mixed conversation-entry history
    port with PostgreSQL message/recall/deletion ordering and V1 ID translation.
  - [x] Expose mixed conversation entries through an additive V2 protocol field
    and update the isolated Web preview before activating the new history path.
  - [x] Add the V2 PostgreSQL attachment metadata registry with membership/device
    ownership, idempotency, hash/size bounds, and explicit lifecycle constraints.
  - [x] Bind canonical attachment messages and typed V1 file identities with
    same-conversation foreign keys before importing any historical file.
  - [x] Define deterministic V1 file/message graph reconciliation with typed
    identities, locator-redacted output, and blocking inconsistency checks.
  - [x] Read both V1 attachment namespaces through query-only SQLite with
    integrity/schema gates and locator-sensitive drift fingerprints.
  - [x] Represent cleared historical files as non-downloadable `UNAVAILABLE`
    metadata without fabricated object key, MIME, or SHA-256 evidence.
  - [x] Bind every active V1 file to a source-fingerprinted manifest of canonical
    object key, exact size/SHA-256, validated MIME, and sealed time.
  - [x] Reconcile the attachment graph against the physically verified backup
    and expose a final-apply re-verifiable evidence capability.
  - [x] Compose text, deferred attachment, and shared sequence inputs under one
    physical backup proof so mixed history retains its original ordering.
  - [x] Add verified V1 file-object evidence and atomically import historical
    attachments, mappings, and attachment messages without local-path leakage.
  - [x] Define and implement the administrator-only canonical PostgreSQL room
    file-list projection with complete V1 mappings and exact quota usage.
  - [x] Compose strict `ROOM_FILES_REQ` transport handling and prove login-to-list
    compatibility through real PostgreSQL.
  - [x] Preserve complete READY and UNAVAILABLE imported attachment messages in
    V1 room history without exposing canonical storage identities.
  - [x] Preserve complete READY and UNAVAILABLE imported attachment messages in
    V1 direct history with the legacy negative friendship file identity.
  - [x] Make V1 room-file deletion administrator-only, serializable,
    retry-idempotent, quota-consistent, and replayable through the shared room
    sequence with first-commit-only local notifications.
  - [x] Define the server-bound V1 room-administrator command contract with
    bounded target identity, convergent role state, and protected OWNER
    semantics before adding persistence or transport.
  - [x] Implement serializable PostgreSQL V1 administrator promotion and
    self-demotion with active mapped membership checks, OWNER protection, and
    convergent duplicate state.
  - [x] Add the detached strict V1 administrator handler with authenticated actor
    binding, bounded off-loop execution, compatible response/status projection,
    and changed-only local target notification.
  - [x] Compose V1 administrator changes in the detached Java compatibility
    module and prove login-to-promotion, duplicate suppression, durable member
    projection, and replacement-login recovery through real PostgreSQL.
  - [x] Define the server-bound V1 room-kick contract with protected roles,
    append-only moderation audit, membership-generation retry convergence, and
    first-commit-only live effects.
  - [x] Add V032 and a serializable PostgreSQL V1 room-kick adapter with atomic
    membership removal, protected-role checks, append-only audit, exact retry,
    and rejoin-generation separation.
  - [x] Add the detached strict V1 room-kick handler with authenticated actor
    binding, bounded off-loop work, compatible response/target/member effects,
    and exact-retry notification suppression.
  - [x] Compose V1 room kicks in the detached Java compatibility module and prove
    login-to-kick, target/member effects, audit linkage, retry suppression, and
    immediate room-list exclusion through real PostgreSQL.
  - [x] Define the strict server-bound V1 room administrative message-deletion
    contract for selected/all/before/after modes, whole-second cutoffs, bounded
    selections, canonical retry fingerprints, and first-commit-only effects.
  - [x] Implement serializable PostgreSQL V1 administrative message deletion
    with atomic target resolution, attachment revocation, recall cleanup,
    shared sequence allocation, durable replay event, and exact retry recovery.
  - [x] Add the detached strict `DELETE_MSGS_REQ` handler with authenticated
    actor binding, bounded off-loop work, compatible response/live effects,
    malformed-frame closure, and explicit saturation handling.
  - [x] Compose V1 administrative message deletion and prove live compatibility,
    exact-retry suppression, file revocation, and reconnect replay through real
    PostgreSQL.
  - [x] Define a convergent server-bound V1 room-rename contract with canonical
    Unicode title bounds, administrator authorization, normalized durable state,
    and changed-only live effects.
  - [x] Implement serializable PostgreSQL room rename with active mapped
    administrator authorization, compare-and-set title updates, convergent
    retries, and durable directory projection.
  - [x] Add the strict detached `RENAME_ROOM_REQ` handler with authenticated
    actor binding, bounded off-loop work, changed-only compatible room effects,
    malformed-frame closure, and explicit saturation handling.
  - [x] Compose V1 room rename and prove changed-only notification suppression
    plus replacement-login directory recovery through real PostgreSQL.
  - [x] Define secure V1 room-password status and mutation contracts with
    clearable plaintext ownership, shared Argon2id encoding, opaque keyed retry
    tags, administrator authorization, and changed-only room effects.
  - [x] Expand canonical credentials with retry tags and implement serializable
    PostgreSQL status/set/replace/cancel convergence with real join verification.
  - [x] Add strict detached password status/mutation handlers with clearable
    JSON secret ownership, authenticated actor binding, bounded off-loop work,
    changed-only compatible effects, and fail-closed malformed/saturation paths.
  - [x] Compose the detached password handlers and prove secret-safe
    compatibility plus replacement-login recovery through real PostgreSQL.
  - [x] Define convergent V1 room dissolution with server-bound administration,
    canonical soft closure, durable attachment cleanup, exact retry identity,
    and post-commit first-only compatibility effects.
  - [x] Implement serializable PostgreSQL room dissolution and cleanup marking.
  - [x] Add the strict detached `DELETE_ROOM_REQ` handler with authoritative
    naming, post-commit first-only effects, malformed closure, and saturation.
  - [x] Compose room dissolution and prove first-only effects plus
    replacement-login absence through real PostgreSQL.
  - [x] Define secure retry-convergent V1 password change with clearable secret
    ownership, current-session binding, Argon2id replacement, other-session
    revocation, non-secret audit intent, and compatible desired-state retry.
  - [x] Implement serializable PostgreSQL credential replacement and audit.
  - [x] Add strict detached `CHANGE_PASSWORD_REQ` decoding and handling with
    clearable dual-secret ownership, auth admission, bounded off-loop crypto,
    generic rejection, malformed closure, and saturation telemetry.
  - [x] Compose password change and prove old/new login, exact retry,
    other-session revocation, and restart durability through PostgreSQL.
  - [x] Define idempotent secure V1 registration with strict identity/display/
    password policy, clearable plaintext, pre-persistence Argon2id, atomic
    numeric compatibility mapping, and exact natural-key retry.
  - [x] Implement PostgreSQL registration allocation and concurrent convergence.
  - [x] Add strict detached `REGISTER_REQ` with auth admission, clearable secret
    decoding, bounded off-loop hashing, generic collision, and login pass-through.
  - [x] Compose registration and prove registration-to-login, exact retry,
    username collision, and restart durability through PostgreSQL.
  - [x] Define and compose convergent V1 nickname change with canonical Unicode
    validation, atomic profile audit, authoritative room audiences, identity
    refresh, exact-retry suppression, and restart recovery.
  - [x] Define and compose cooldown-bound V1 username change with stable account
    identity, atomic uniqueness/audit, authoritative peer audiences, exact-retry
    convergence, new-name login recovery, and old-name denial.
  - [x] Define the object-backed user/room avatar boundary with owned V1 bytes,
    bounded canonical image evidence, private immutable objects, metadata-only
    PostgreSQL state, authorization, cleanup, and compatibility-only Base64.
  - [ ] Implement and compose the bounded avatar inspector, object lifecycle,
    PostgreSQL metadata/import, strict V1 handlers, and restart recovery after
    the real-provider capability gate passes.
    - [x] Add V040 metadata-only content-addressed object registration, versioned
      account/group pointers, non-byte audit, complete room audiences, shared-
      reference-aware cleanup intent, and real PostgreSQL verification.
    - [x] Add authorized metadata reads for enabled mapped usernames and active
      room members, with missing-vs-denied outcomes and no provider URL exposure.
    - [x] Add owned private-object read orchestration plus an inactive S3 reader
      that requires exact length, media type, provider checksum, application
      SHA-256, and canonical PNG evidence before returning bytes.
    - [x] Add detached strict `AVATAR_GET_REQ`/`ROOM_AVATAR_GET_REQ` handlers with
      authenticated targets, bounded off-loop reads, compatibility-only Base64,
      fixed-cardinality telemetry, and fail-closed storage-integrity handling.
      Keep them uncomposed until the real-provider capability gate passes.
    - [x] Add inactive mutation orchestration that authorizes before image work,
      canonicalizes owned V1 bytes, requires exact content-addressed object
      evidence before pointer commit, and durably requests orphan cleanup when a
      newly created object cannot be committed.
    - [x] Add PostgreSQL mutation preflight for enabled mapped accounts and
      active mapped room administrators, plus serializable exact-evidence orphan
      registration that marks cleanup only while no account/group pointer refers
      to the object; verify the races and constraints against real PostgreSQL.
    - [x] Add an inactive S3 create-only profile-image writer with exact PUT
      length/type/SHA-256 constraints, no-overwrite precondition, checksum-
      verified success, and exact HEAD convergence for already-present content.
    - [x] Add detached strict user/room V1 avatar upload handlers with canonical
      Base64 limits, clearable ownership, authenticated mutation targets,
      bounded off-loop work, compatible responses, first-commit-only local
      notifications, fixed telemetry, and no object-evidence disclosure.
      Keep them uncomposed pending the real-provider gate.
    - [x] Define ADR-0319 and V041 leased profile-image cleanup state, plus the
      inactive bounded claim-delete-confirm application service with stale-claim
      recovery, exact tokens, provider-failure release, and real PostgreSQL
      migration/constraint verification.
    - [x] Add PostgreSQL cleanup claiming with bounded `SKIP LOCKED` batches,
      stale-token replacement, exact release/confirmation, reference exclusion,
      active-claim pointer rejection, and confirmed-object revival; verify the
      complete lifecycle against real PostgreSQL.
    - [x] Add an inactive S3 profile-image deleter that accepts only canonical
      content-addressed private keys, treats provider 404 as idempotent success,
      and surfaces denial/failure for leased retry.
    - [x] Add an explicitly started, non-overlapping inactive cleanup loop with
      fixed-cardinality counters, capped exponential failure backoff, recovery
      reset, and clean cancellation; do not compose it before provider evidence.
    - [x] Add a separately confirmed, auto-cleaning real-provider profile-image
      probe for fresh create-only PUT, exact retry, checksum-bound GET, DELETE,
      and final absence, with secret-safe output and a non-production runbook.
    - [x] Add proof-bound, deterministic V1 SQLite user/room avatar extraction
      with explicit absent/invalid records, bounded canonical PNG objects,
      content deduplication, and a SHA-256 manifest. The export is offline
      evidence only.
    - [x] Add an independent strict export verifier requiring the expected
      manifest hash, proof binding, exact ordered/count-reconciled records,
      canonical object bytes, and a no-symlink/no-extra-path directory tree.
      Object-storage/PostgreSQL import still remains.
    - [x] Define ADR-0320 and add V042 immutable manifest/entry import evidence
      with explicit absence, account/group target constraints, and exact-manifest
      retry convergence. Import application logic still remains.
    - [x] Add a read-only PostgreSQL pre-provider preview for every historical
      account/room mapping, availability, empty target pointer, exact registered
      object evidence, active delete claims, and prior manifest runs.
    - [x] Add inactive bounded upload orchestration that rechecks each unique
      canonical object, performs create-only Provider convergence even when
      PostgreSQL metadata already exists, and produces an exact-evidence apply
      capability.
    - [x] Add serializable PostgreSQL historical apply with exact object
      registration/revival, version-1 account/group pointers, explicit absence
      audit, no fabricated user-change events, whole-transaction rollback, and
      retained-run exact retry/restart reconciliation.
    - [x] Compose the guarded offline apply command in strict verify-preview-
      provider-reverify-transaction order, with explicit destructive/import/
      reviewed-evidence confirmations, safe output, and exact-retry recovery.
    - [ ] Retain dated profile-image provider PASS, policy, lifecycle,
      no-object-remains, lease/timeout, rollback, and restart evidence; this
      destructive probe has not run in repository CI or this development host.
  - [x] Add the inactive attachment registration application port and exact
    PostgreSQL idempotency/authorization adapter before issuing upload grants.
  - [x] Add the inactive provider-neutral create-only upload grant and sealed-
    object completion orchestration with bounded expiry and fail-closed checks.
  - [x] Allocate the inactive V2 attachment register/authorize/complete protocol
    with bounded payload policy and Java/C++/TypeScript golden compatibility.
  - [x] Add the inactive authenticated V2 attachment command handler with
    server-bound identity, bounded off-loop work, safe errors, and fixed telemetry.
  - [x] Add the inactive Web V2 attachment coordinator with transient SHA/grants,
    direct create-only PUT, expiry refresh, cancellation, and no durable bytes.
  - [x] Add the authorization-rechecked PostgreSQL attachment lifecycle adapter
    with concurrent idempotent READY transition.
  - [x] Add the inactive S3-compatible simple-PUT adapter with signed create-
    only/checksum constraints, checksum-enabled HEAD, and locked dependencies.
  - [x] Add strict inactive S3 runtime configuration, injected credential-
    provider ownership, explicit JDK HTTP transport, and deterministic closure.
  - [x] Add the durable PostgreSQL marker and indexed retry set for attachment
    object cleanup using revoke-delete-confirm ordering.
  - [x] Add inactive bounded cleanup orchestration with failure isolation and
    fixed revoke/attempt/delete/provider/confirmation outcome counters.
  - [x] Add PostgreSQL/S3 cleanup adapters with concurrent `SKIP LOCKED`
    revocation, retry paging, idempotent confirmation, and scoped object delete.
  - [x] Add a manually activated non-overlapping cleanup loop, bounded
    exponential backoff, fixed telemetry, and loopback Prometheus export.
  - [ ] Pass real-provider create-only/checksum/CORS/delete capability acceptance
    before composing or activating upload and cleanup commands.
    - [x] Add an explicitly confirmed, auto-cleaning probe with fixed safe output
      and a dedicated non-production-bucket runbook.
    - [ ] Retain dated real-provider PASS, expiry, policy, lifecycle, and
      no-object-remains evidence; no cloud probe has run in repository CI.
  - [x] Add WAL-aware query-only SQLite extraction, quick-check, current-schema
    enforcement, bounded wait, and safe UTC timestamp projection.
  - [x] Add WAL-consistent SQLite online backup, no-overwrite artifact creation,
    source/backup plan reconciliation, and hash/size/time proof.
  - [x] Add PostgreSQL dry-run/apply conflict checks, serializable post-write
    reconciliation, in-transaction proof persistence, and conflict rollback
    verification.
  - [x] Add a separate offline backup/verify/preview/apply command, versioned
    proof file, explicit fingerprint confirmation, safe output, and disposable
    PostgreSQL command-boundary verification.
  - [x] Add transport-independent message append/history ports and a PostgreSQL
    adapter with active account/member/device authorization, atomic sequence,
    exact concurrent idempotency, database timestamps, and bounded cursor pages.
  - [x] Add the canonical conversation-directory application port and V004
    group profile migration with active-membership, stable composite-cursor
    PostgreSQL projection.
  - [ ] Rehearse the operator restore procedure and quiesced final fingerprint
    check before any identity authority cutover.
    - [x] Add a PostgreSQL-independent final source/backup/proof/fingerprint gate
      that rejects source drift before target writes without claiming it proves
      writer quiescence.
    - [x] Automate a timed isolated C++ V1 server backup/restore rehearsal that
      verifies both credential generations and durable history, and records
      explicitly non-production evidence.
    - [ ] Record independently verified production-topology writer shutdown
      evidence for the maintenance-window rehearsal.
- [x] Define the transport/persistence-independent fresh-login application use
  case, outward identity/session ports, generic rejection, and explicit secret
  zeroing lifecycle.
- [x] Implement exact V1-compatible PostgreSQL account lookup and transactional
  device/session issuance with random-token digest storage, device reuse, and
  revoked/disabled race denial.
- [x] Verify V1/libsodium Argon2id hashes through a bounded Bouncy Castle adapter
  with a cross-implementation vector, constant-cost missing/malformed dummy
  work, and parameter/resource caps.
- [x] Implement legacy salted-SHA storage/verification compatibility and
  compare-and-set Argon2id upgrade without forcing dormant-account resets.
- [x] Implement the verified one-way V1 data import and session resume/rotation.
  - [x] Define the transport-independent owned resume command, atomic-rotation
    persistence port, generic result, and explicit token zeroing lifecycle.
  - [x] Implement PostgreSQL digest verification, row-locked proof rotation,
    concurrent/sequential replay denial, device binding, expiry and revocation.
  - [x] Connect the bounded gateway resume command, generic denial, rotated
    response, secret cleanup, and server-bound connection identity.

Work:

- create a Gradle multi-module Java workspace (foundation complete; domain
  modules are extracted with vertical slices);
- implement the V2 envelope and generated Java/C++/TypeScript schemas (complete);
- build the Netty gateway and modular application core;
- introduce PostgreSQL schema and repeatable migrations (foundation complete;
  repositories and V1 import remain);
- implement identity/device sessions, canonical conversations, message
  idempotency, per-conversation sequences, and incremental sync;
- keep a V1 JSON adapter at the gateway boundary;
- migrate data through verified one-way jobs with backup and rollback plans;
- switch one vertical slice at a time: authentication, conversations, messages,
  contacts, groups, then attachments.

Exit criteria:

- V1 compatible clients and V2 clients can use the authoritative Java backend;
- one database is the documented source of truth during each cutover stage;
- the old C++ server can be restored within the documented rollback window;
- protocol compatibility and data migration tests pass in CI.

## M4 — Windows and Web Distribution

Goal: deliver trustworthy Windows installation and rollback-ready Web releases.

Progress:

- [x] Establish one canonical Windows desktop version source and a deterministic,
  client-only, explicitly unsigned verification-payload manifest tied to source.
- [x] Add an unsigned NSIS direct-installer skeleton with native CI install,
  uninstall, registry traceability, and account-local data preservation checks.
- [x] Stage and atomically swap installer-owned Windows program directories,
  restore the old directory on activation failure, reject direct downgrades,
  and configure a synthetic version-upgrade/data-preservation CI gate.
- [x] Define and cryptographically exercise a canonical, detached-Ed25519
  stable/beta Windows update manifest without enabling a product update key.
- [x] Make offline Windows update-manifest parsing duplicate-key strict and
  detached signature output write-once before introducing production key custody.
- [x] Add a protected PKCS#11/OpenSSL 3 update-manifest signing primitive that
  accepts no exportable private key or credential input and verifies every
  write-once signature against a reviewed public-key identity.
- [x] Bind an exact schema-5 signed Windows candidate, canonical Ed25519 update
  manifest/signature, and reviewed public PEM into one closed, immutable,
  durably verifiable, explicitly unpublished update-channel candidate.
- [x] Orchestrate that candidate on a distinct protected update-signing
  environment/runner, consuming exactly one prior signed Windows artifact and
  emitting one short-retention candidate without publication authority.
- [x] Add a short-lived write-once Windows update promotion authorization that
  reconstructs the signed candidate, binds a canonical expected-current
  manifest for compare-and-swap, and requires a strictly advancing sequence.
- [x] Add immutable content-addressed Windows update release staging with full
  candidate revalidation and no activation or network authority.
- [x] Consume update promotion authorization once, compare-and-swap an exact
  existing channel pointer to a pre-staged release, and restore the old pointer
  on final-validation/evidence failure while awaiting external observation.
- [x] Observe the exact canonical manifest, detached signature, and Setup bytes
  through strict trusted HTTPS with deliberate security and cache policy.
- [x] Bind atomic execution and a matching post-switch HTTPS observation into
  immutable, bounded production update-promotion completion evidence.
- [x] Derive a one-time B-to-A rollout halt from completion evidence, require a
  still-valid retained A manifest, and never reactivate failed B after an
  evidence-write failure; affected B clients use a forward corrective release.
- [x] Bind a matching post-rollback HTTPS observation of restored A into
  bounded immutable rollout-halt completion evidence.
- [x] Define a short-lived reviewed Windows product-update trust intent binding
  exact source/version/channel/URL and one/two canonical Ed25519 public keys,
  while ordinary builds remain disabled.
- [x] Add a pre-UI, side-effect-free final-binary diagnostic for exact compiled
  public update trust and require ordinary native CI to prove disabled/empty.
- [x] Bind an exact unsigned `ChatClient.exe`, its strict trust diagnostic, and
  the live reviewed intent into immutable compiled-public-trust evidence.
- [x] Advance unsigned Windows artifacts to schema 4 with explicit null or
  fully closed product update trust and a fail-closed required-trust intake.
- [x] Add a distinct protected native Windows build that consumes the exact
  ordinary null-trust artifact, changes only the reviewed client trust PE,
  proves installed diagnostic parity, and emits an unsigned/unpublished
  trust-required schema-4 artifact.
- [x] Require that artifact at protected Authenticode intake, re-attest public
  update trust from the signed client PE, and close it in signed candidate
  schema 6 beside signature and native install evidence.
- [x] Define privacy-preserving stable/beta rollout steps and a fail-closed
  advisory health decision that holds incomplete data and recommends halt only
  after minimum event counts plus rate breaches.
- [x] Advance general promotion authorization to schema 2 and reject same-
  version/source rollout-percentage changes so health-bound expansion cannot be
  bypassed through the ordinary release path.
- [x] Add a dedicated short-lived rollout-expansion authorization that
  reconstructs promotion/health, verifies canonical aggregate metrics through
  a reviewed Ed25519 exporter key, preserves binary/installer/seed identity,
  and permits only the next signed percentage step without executing it.
- [x] Consume rollout-expansion authorization once, compare-and-swap exact
  pre-staged current/target releases, preserve cohort identity, restore the old
  pointer after finalization failure, and await external observation.
- [x] Bind a strict post-switch HTTPS observation to expansion execution in a
  bounded immutable completion record retaining percentages and cohort seed.
- [x] Require every update-channel candidate's manifest signing key ID and PEM
  bytes to match an exact primary/secondary key compiled into the signed client.
- [x] Add a dedicated short-lived forward-fix authorization after an observed
  rollout halt, requiring a higher version/sequence, new source, 100 percent
  rollout, B-compatible minimum version, and an update key compiled into B.
- [x] Persist an exclusive open rollout-incident marker during B-to-A rollback
  and make ordinary promotion/expansion execution fail closed until the
  dedicated forward-fix path resolves it.
- [x] Consume the incident-bound forward-fix authorization once, require A as
  the exact current pointer and pre-staged C as target, atomically switch to C,
  restore A on finalization failure, and keep the incident open for observation.
- [x] Bind a bounded strict HTTPS observation of exact C to forward-fix
  execution, retain immutable recovery completion/resolution evidence, and only
  then close the rollout incident gate.
- [x] Compile a default-deny canonical Ed25519 verifier into the Windows client,
  align sequence precision, and package its pinned libsodium runtime without a
  trusted product key or network activation.
- [x] Add a default-off Windows update decision policy for exact schema and
  architecture, UTC validity, per-channel replay state, numeric versions,
  deterministic staged rollout, and bounded installer metadata.
- [x] Add a default-off installer trust verifier for exact size/SHA-256,
  Windows Authenticode chain/revocation, RFC 3161 counter-signature presence,
  and signed leaf-certificate thumbprint matching.
- [x] Add default-off, owner-only atomic device/manifest replay state with one
  stable UUIDv4 and sequence/digest high-watermark per channel across key
  rotations.
- [x] Enforce signature verification, semantic policy, and atomic replay
  acceptance ordering behind one default-off update application service.
- [x] Add a dedicated default-off HTTPS installer transport with a shared 2 GiB
  signed bound, no redirects/credentials, timeout/cancel, private partial
  staging, and failure cleanup.
- [x] Compose signed eligibility, bounded download, and background installer
  trust behind one default-off preparation service that exposes only verified
  bytes and deletes every rejected/cancelled file.
- [x] Add Windows single-instance liveness and make NSIS reject an open client
  before mutation, with native CI configured to prove exit code and unchanged
  program/account state.
- [x] Add a default-off, bounded HTTPS transport for the exact manifest and
  detached-signature pair, with same-origin path binding, no redirects,
  timeout/cancel, and no unverified-byte exposure after failure.
- [x] Compose discovery, signature/replay/policy acceptance, bounded installer
  transfer, and background installer trust behind one default-off check service
  with explicit no-update/manual/deferred/rejected/cancelled outcomes.
- [x] Add a Windows-only locked re-verification-and-launch primitive that holds
  Setup against replacement through `CreateProcess`, permits only silent NSIS,
  waits with a bound, and reports the actual installer exit code.
- [x] Add and package a fail-closed external Windows update helper with strict
  one-shot arguments, parent-exit handshake/wait, atomic result evidence,
  unsigned rejection, cleanup, and success-only client restart.
- [x] Preserve the signed installer size, SHA-256, and publisher thumbprint in a
  typed `PreparedInstaller` handoff from background trust through the complete
  update-check boundary instead of reducing readiness to a mutable path.
- [x] Add an inactive asynchronous Windows handoff service that stages the
  helper plus matching Qt Core outside the program directory, generates the
  one-shot command/event/result contract, and authorizes normal quit only after
  the helper has opened the exact parent process and signaled readiness.
- [x] Define a strict client-side schema-1 launcher-result parser that binds the
  expected request UUID and rejects unknown fields/outcomes, contradictory exit
  codes, unsafe text, and implausible timestamps.
- [x] Add an inactive owner-only update lifecycle repository that persists one
  pending UUID/version/time across exit, derives result/run paths, consumes valid
  evidence once, and retains missing or invalid evidence without claiming success.
- [x] Add an inactive install coordinator that rejects parallel work and permits
  normal client quit only after both the helper handshake and atomic pending
  lifecycle persistence succeed.
- [x] Activate Windows startup reconciliation for UUID-bound update results,
  require the running binary version to match reported success, and avoid
  blocking a recent in-progress Setup while permitting stale-state recovery.
- [x] Add a default-off compiled Windows update product configuration with exact
  stable/beta HTTPS origin policy and a reviewed one/two-public-key rotation
  ring; keep all private signing material outside client and build arguments.
- [x] Replace the inactive one-phase helper handoff with a two-phase
  ready/persist/commit barrier so persistence failure can never become a later
  untracked Setup launch after the user exits.
- [x] Activate discovery and installation UX only for compiled-trust Windows
  builds, with first-login/manual checks, cancellable preparation, default-No
  consent, prepared-file cleanup, and the normal draft/disconnect quit path.
- [x] Add a provider-neutral post-signing Windows release evidence boundary for
  the client, update helper, uninstaller, and final Setup, and prove unsigned artifacts fail
  without producing evidence in native CI.
- [x] Independently revalidate Windows signature evidence against the exact
  final client/helper/uninstaller/Setup bytes, closed schema, release identity, and freshness
  before any future publication step.
- [x] Atomically assemble and independently verify one immutable full Windows
  release candidate containing the signed subjects, complete Qt/SQLite/libsodium
  payload, strict file inventory, and no server/debug/key material.
- [x] Establish an independently versioned Web verification artifact with
  hashed local assets, source-map rejection, and explicit cache classes.
- [x] Pin V1 production WebSocket and file traffic to the HTTPS page origin,
  remove legacy browser server overrides, and retain loopback-only local
  development behavior.
- [x] Bind a provider-neutral CSP/HSTS/cache/release-identity response policy to
  the versioned Web verification artifact with fail-closed mutation tests.
- [x] Add an immutable Web release store, atomic activation pointer, integrity
  health output, and isolated A-to-B-to-A rollback rehearsal without rebuilding.
- [x] Add a provider-neutral HTTPS release probe that observes exact security,
  cache, identity, TLS, no-redirect, and immutable-byte behavior in isolation.
- [x] Persist closed, write-once Web HTTPS observations, independently reverify
  them against the immutable release, and bind prior-A/current-B/restored-A
  observations into tamper-evident no-rebuild rollback evidence.
- [x] Add an exact, query-free, credential-free V1 `/api/health` contract so the
  Web release gate can distinguish working same-origin application routing from
  a proxy/CDN 404 without exposing application or dependency state.
- [x] Observe `/api/health` and an RFC 6455 nonce-bound `/ws` upgrade on one
  trusted HTTPS origin, with closed write-once route evidence and fail-closed
  redirect, TLS, path, response, and mutation tests.
- [x] Bind a fresh candidate static observation, fresh route observation, exact
  immutable artifact, and a distinct retained rollback artifact/observation into
  one independently verifiable, explicitly not-published technical promotion record.
- [x] Advance Web technical promotion/authorization to schema 2 with a distinct
  candidate preview origin and production rollback origin, so B is verified
  without pretending A and B occupy one production root URL.
- [x] Add an isolated atomic Web preview selector over the same immutable
  release store and prove selecting B cannot mutate production's active A.
- [x] Add a separate write-once, short-lived `web-production` authorization that
  revalidates and binds the exact technical promotion without containing provider
  credentials or executing traffic mutation.
- [x] Add a one-time authorization consumer for atomic Web release pointers with
  expected-current rollback identity, replay prevention, failure restoration,
  and explicit pending-external-observation evidence.
- [x] Bind fresh post-switch HTTPS/static and application-route observations to
  pointer execution in a write-once, independently verifiable production
  completion record that rejects preview-observation reuse.
- [x] Add a one-time incident rollback consumer derived from durable execution
  evidence, requiring B as current and restoring only pre-authorized A without
  switching back to failed B on evidence-write failure.
- [x] Bind post-rollback strict HTTPS observation of exact A and same-origin
  `/api/health` plus `/ws` routing to rollback execution in a bounded immutable
  production rollback-completion record.
- [x] Add a two-stage Web production workflow that verifies preview B and
  production A before approval, refreshes evidence after approval, atomically
  promotes B, and automatically observes rollback on post-switch failure.
- [x] Define a write-once preview/production health window requiring at least
  three unique static plus API/WebSocket observation pairs over a bounded
  duration; workflow integration and real observations remain open.
- [x] Gate the Web production workflow on pre-approval preview, post-approval
  preview, and post-switch production health windows, with production failure
  entering the pre-authorized rollback path; no real run claimed.
- [x] Independently close the reviewed preview window, pointer execution,
  production window, and production completion as one write-once staged Web
  release result; no real run claimed.
- [x] Establish a root CMake Release path for the exact V1 HeadlessServer
  production sources, Qt/libsodium dependency discovery, AUTOMOC, CI build, and
  real process-health test while leaving Windows product packaging on qmake.
- [x] Extract reusable CMake V1 persistence/server-core static targets, keep the
  executable entry point thin, and migrate the unchanged clean/restart/query-plan
  SQLite schema suite to CTest.
- [x] Reuse the CMake persistence boundary for the unchanged Argon2id/legacy
  password migration, wrong-password, change-password, and restart CTest suite.
- [x] Extract CMake Common and non-UI Windows client local-data libraries and
  execute the existing message model, SQLite repository, optimistic send, sync,
  attachment outbox, and V1 history adapter suites through CTest.
- [x] Extract the Windows client V1 TCP/raw-HTTP transport CMake library and run
  exact upload, streamed download/denial cleanup, and memory-only reconnect/
  rejected-restore tests with bounded CTest timeouts.
- [x] Remove the Windows V1 optional TLS `VerifyNone` path, bind the expected
  peer name, delay application readiness until `encrypted`, and prove runtime
  rejection/acceptance with ephemeral untrusted/trusted certificate tests.
- [x] Extract the portable Windows update signature/decision/replay-state/
  application trust core into CMake and execute four existing tamper, policy,
  atomic-state, and non-bypassable-order suites through the M4 CTest gate.
- [x] Separate Windows update manifest/Setup transport from installer trust in
  CMake and run fail-closed URL, redirect, bound, cancellation, cleanup,
  integrity, unsupported-platform, and unsigned-launch tests.
- [x] Compose those CMake boundaries through the existing preparation and
  complete-check application services, preserving verify/decide/accept before
  download and installer trust before a typed prepared handoff.
- [x] Extract CMake lifecycle, helper-command, startup reconciliation, and
  two-phase handoff/install-coordination boundaries with closed evidence,
  replay, parallel-work, persistence-before-quit, and cleanup tests.
- [x] Add Windows-only CMake `ChatClient` and update-helper verification targets,
  canonical PE version/icon resources, qmake source-graph parity policy, and a
  native MSVC build lane while retaining qmake packaging as rollback.
- [x] Independently deploy the CMake Windows targets in native CI, require exact
  qmake/CMake runtime inventory and non-executable byte parity, and bind the
  closed comparison evidence into the uploaded artifact manifest.
- [x] Compile a separate temporary NSIS from the CMake payload and exercise
  clean install, PE/runtime checks, client launch, helper two-phase unsigned
  rejection/cleanup, uninstall, and account-data preservation in native CI.
- [x] Extend the temporary CMake NSIS gate through a synthetic predecessor
  upgrade, stale-program replacement, traceable registration, running-client
  mutation refusal, downgrade refusal, and final uninstall/data preservation.
- [x] Promote the verified CMake deployment as canonical Windows artifact/NSIS
  input, retain qmake as a parity fallback, and bind `buildSystem: cmake` plus
  exact parity-candidate hashes into artifact-manifest schema 4.
- [x] Add fail-closed CMake stable/beta update configuration injection with
  disabled-residue, HTTPS literal, one/two-public-key validation and execute
  default-off/enabled runtime configuration suites through CTest.
- [x] Add an independent protected-signing intake verifier for the complete
  schema-4 unsigned CMake artifact, closed identity/inventory/checksums, exact
  final bytes, parity evidence, required runtimes, and zero extra files.
- [x] Define a fresh closed protected-signing intent binding version/revision,
  unsigned artifact run/name, channel, certificate SHA-1/SHA-256 identities,
  RFC 3161 URL, protected environment, and signing runner class without secrets.
- [x] Advance the immutable Windows candidate to schema 2, require the protected
  signing intent during assemble/verify, and bind its bytes into the candidate
  file inventory and checksums with independent semantic revalidation.
- [x] Add an explicit NSIS `RELEASE_BUILD` output mode for the canonical
  `ChatRoom-<version>-Setup.exe` identity while keeping ordinary CI explicitly
  unsigned and prohibiting implicit NSIS signing commands.
- [x] Add a manual protected-environment/self-hosted Windows signing workflow
  that verifies exact unsigned intake and intent, uses only a machine-store
  certificate, signs/timestamps three subjects, and emits a verified unpublished
  schema-2 candidate without release or update-channel mutation.
- [x] Add a two-pass NSIS release boundary that exports the generated uninstaller
  without signing inside NSIS and imports the externally signed exact bytes,
  while leaving ordinary unsigned installer behavior unchanged.
- [x] Sign the exported uninstaller as a fourth protected subject, advance
  signature evidence to schema 2 and the immutable candidate to schema 3, and
  retain its exact bytes for independent verification and installed-byte proof.
- [x] Add a fail-closed native install/uninstall acceptance step that compares
  installed client/helper/uninstaller bytes and signatures, validates product
  registration and cleanup, and binds independently verified evidence into
  candidate schema 4.
- [x] Advance the candidate to schema 5 with an immutable assembly timestamp so
  live inputs must be fresh during assembly while archived candidates remain
  durably auditable against that trusted instant.
- [x] Pin a Chromium/Firefox Playwright engine matrix and exercise login startup,
  browser capabilities, endpoint isolation, and narrow responsive layout.
- [x] Define six branded current/previous Chrome, Edge, and Firefox support
  slots and a candidate-bound protected workflow for version-dedicated hosts,
  without browser-install or production-mutation authority; no real run claimed.
- [x] Close exactly six fresh branded-browser records against one immutable Web
  candidate and strictly ordered current/previous versions in a separately
  reverified, 90-day completion artifact; no real run claimed.
- [x] Advance branded-browser evidence to require runtime keyboard traversal,
  Enter submission, and announced login validation errors on every slot.
- [x] Make the supported V1 Web transport pause sockets/retries while the
  browser is offline, reconnect once on recovery, and announce cached/offline
  state without treating `online` as gateway health.
- [x] Advance branded-browser evidence to verify that offline login creates no
  WebSocket, announces the state, and requires an explicit retry after recovery.
- [x] Advance branded-browser evidence with a deterministic V1 client fixture
  that verifies the post-login shell, memory-only credentials, retained offline
  UI, and exactly one recovery reauthentication request without claiming server
  authentication.
- [x] Require real metadata decoding of tiny synthetic VP9 WebM and Opus/Ogg
  fixtures on every branded browser slot instead of trusting codec declarations.
- [x] Require the production login, lazy chat shell, offline UI, and recovery
  journey to pass with fixed 250ms document/script/style response latency,
  without presenting it as a bandwidth or capacity benchmark.
- [x] Run the native V1 schema-1-to-3 IndexedDB migration in every branded
  browser slot and prove bounded retention plus legacy media/secret removal.
- [x] Fail fast when a legacy tab blocks IndexedDB upgrade, keep the authenticated
  shell usable, and retry successfully on the next cache operation after release.
- [x] Pin exact Windows 10 22H2 and Windows 11 23H2/24H2 x86_64 client targets
  and independently verify a per-host, two-real-signed-candidate install/launch/
  upgrade/data-preservation/downgrade/uninstall evidence contract.
- [x] Add a reviewed, read-only native support-matrix workflow that consumes
  exact prior/current signed candidates on dedicated clean Windows client hosts
  and retains independently verified evidence without signing/publication power.
- [x] Close all three per-host results against one exact prior/current signed
  candidate transition in a separate immutable support-matrix completion record.
- [ ] Build, sign, timestamp, install, upgrade, uninstall, and roll back the
  supported Windows installer and update channel.
- [ ] Publish and verify versioned, policy-hardened, rollback-ready Web releases.

Work:

- sign and timestamp the canonical CMake/`windeployqt` Windows installer;
- add an optional MSIX/Store channel after the direct installer is stable;
- implement a signed Windows update manifest, stable/beta channels, staged
  rollout, trusted endpoint/key provisioning, and rollback;
- publish versioned Web assets with CSP, deliberate cache/source-map policy,
  health checks, staged rollout, and fast rollback;
- test Windows clean install, upgrade, protocol compatibility, and uninstall on
  native CI, and test Web deployment/rollback in an isolated environment.

Exit criteria:

- Windows users install without warnings caused by missing project signing;
- Windows upgrades preserve local message data and settings;
- a Web release is versioned, observable, and can be rolled back without
  rebuilding;
- a failed rollout can be stopped and rolled back;
- release artifacts are reproducible, versioned, and traceable to source.

## M5 — Horizontal Scale and Asynchronous Delivery

Goal: scale only after the modular backend and measurements justify it.

Progress:

- [x] Add a reproducible disposable-PostgreSQL Java V2 messaging baseline with
  strict non-capacity evidence validation for sequential commit, idempotent
  retry, same-conversation contention, bounded history, CPU, heap, and RSS.
- [x] Exercise the production single-gateway TLS/WSS submit-to-accept path with
  real Protobuf WebSocket clients, caught-up peer live fan-out, and disposable
  PostgreSQL before using that path for load evidence.
- [x] Measure a reproducible bounded single-gateway TLS/WSS path with connection
  setup, submit-to-accept, caught-up peer fan-out, throughput, CPU, heap, RSS,
  zero-error, and durable sequence evidence.
- [x] Parameterize the same production path for a bounded active GROUP with up
  to 59 authenticated receivers under the default peer admission limit,
  all-peer completion latency, and exact aggregate publication reconciliation
  while preserving schema-1 evidence.
- [x] Add bounded concurrent same-session resume rounds to the production WSS
  path, requiring exact session identity, per-round token rotation, exact
  operation counts, and schema-3 zero-error evidence while preserving older
  evidence contracts.
- [x] Record a clean, commit-exact 10-connection/5-round session-resume baseline
  without weakening the default direct-peer authentication admission window.
- [x] Add a deterministic gateway-router correctness gate proving an unwritable
  subscriber is closed without blocking a healthy peer and can re-establish its
  subscription from durable history before live delivery resumes.
- [x] Add a real TLS/WSS schema-4 slow-consumer scenario that preserves healthy
  peer delivery, observes exactly one production closure metric, resumes the
  same session, repairs every missing sequence in envelope-safe pages, and
  proves restored live delivery without lowering production write watermarks.
- [x] Record a clean, commit-exact five-connection slow-consumer baseline with
  64 KiB valid text, three uninterrupted healthy receivers, one production
  closure action, exact history repair, and a post-recovery live probe.
- [x] Add a gateway correctness gate for an ambiguous PostgreSQL submission
  outcome: return a retryable redacted error, keep the connection active, retry
  the identical `clientMessageId`, and converge on the original message ID and
  sequence as a duplicate without a second live publication.
- [x] Make gateway readiness depend on obtaining and validating a PostgreSQL
  connection after process startup, fail closed without exposing database error
  details, and recover automatically when the dependency is valid again while
  keeping liveness independent.
- [x] Add a schema-5 real WSS/two-connection-pool saturation scenario using a
  disposable-database-only delay trigger, requiring mixed initial success and
  retryable failure, readiness 503, same-ID convergence after pressure removal,
  readiness 200 recovery, exact unique publications, and durable sequences.
- [x] Record a clean, commit-exact 13-connection PostgreSQL pool-saturation
  baseline with eight concurrent senders, six retryable timeouts, same-ID
  convergence, 503-to-200 readiness recovery, and 32 unique peer publications.
- [x] Add a schema-6 disposable-PostgreSQL stop/start scenario that keeps the
  production gateway and authenticated WSS sessions alive, requires a redacted
  retryable outage response, liveness 200/readiness 503 separation, automatic
  readiness recovery, same-ID retry on the original connection, one durable
  sequence, and one publication per peer.
- [x] Record a clean, commit-exact five-connection PostgreSQL stop/start
  recovery baseline with a 5.917 ms retryable outage response, liveness
  200/readiness 503 separation, readiness recovery in 307.830 ms, original-WSS
  same-ID retry, one durable sequence, and four exact peer publications.
- [x] Replace the one-active-conversation-per-channel preview with up to 100
  retained, authorized, caught-up process-local subscriptions, preserving
  failed-history isolation, close cleanup, and sequence-history repair
  (ADR-0345).
- [x] Add a schema-7 production WSS/PostgreSQL active-conversation scenario with
  even per-conversation operations, exact membership/subscription counts,
  activation latency, all-peer publications, and independent durable sequence
  reconciliation while preserving schema 1–6 evidence.
- [x] Record a clean, commit-exact 10-active-GROUP curve with five WSS
  connections, 40 retained routes, 110 independently reconciled messages, 400
  publications, 1.483 ms all-peer P95, and zero errors.
- [x] Record the comparable clean 100-active-GROUP route-bound curve with five
  WSS connections, 400 retained routes, 200 independently reconciled messages,
  activation P95 87.599 ms, all-peer publication P95 1.060 ms, and zero errors;
  the single-gateway measurements do not yet justify Redis or a broker.
- [x] Record a clean current-revision 40-receiver GROUP fan-out point with 41
  WSS connections, 4,000 exact publications, all-peer P95 2.405 ms, continuous
  durability, and zero errors; no current single-gateway fan-out evidence
  justifies a broker.
- [x] Add a schema-8 controlled reconnect-arrival scenario with explicit batch
  size/interval, fixed scheduled span/rate, measured scheduling jitter, exact
  session/account/device identity, token rotation, default admission-window
  preservation, and zero-error evidence while preserving schema 1–7.
- [x] Record a clean 10-connection/5-round curve with two resumes per batch at
  100 ms intervals: 8.878 ms resume P50, 12.798 ms P95, about 10 ms maximum
  scheduled-start jitter, exact token rotation, and zero errors.
- [x] Record the comparable 25 ms curve: 6.448 ms resume P50, 9.728 ms P95,
  85.994 paced resumes per second, about 10 ms maximum scheduled-start jitter,
  exact identity/token rotation, and zero errors.
- [x] Retain the Web/Windows V2 full-jitter exponential reconnect policy (500 ms
  initial ceiling, 30 second cap); the local 25/100 ms curves do not establish a
  production fleet rate. Define the future rolling-drain order as readiness
  removal, bounded connection drain, then randomized client reconnect, pending
  a multi-gateway implementation and failure validation.
- [x] Implement the first bounded Java gateway drain boundary: readiness goes
  false before listener admission stops, established children receive a
  configurable 0..300 second monotonic window, timeout forces cleanup, the
  loopback admin endpoint remains observable, and fixed lifecycle outcomes
  distinguish completion from timeout (ADR-0346).
- [x] Add a fixed-cardinality slow-consumer backlog gauge that records the
  process maximum bytes an unwritable channel must drain before becoming
  writable, sampled at the existing close decision without identity labels or
  internal Netty buffer access (ADR-0347).
- [x] Upgrade new real TLS slow-consumer evidence to schema 9, requiring a
  positive production drain-byte observation while preserving historical
  schema-4 validation, exact healthy-peer delivery, session resume, bounded
  history repair, and the post-recovery live probe.
- [x] Record a clean, commit-exact five-connection schema-9 point: one closure
  after 16 maximum-size messages, 200,253 bytes reported to restore
  writability, 48 uninterrupted healthy-peer publications, exact 16-sequence
  repair, restored live delivery, and zero errors.
- [x] Measure many conversations, large active groups, controlled reconnect
  arrivals, slow consumers, PostgreSQL saturation, and dependency failure before
  selecting distributed infrastructure.
- [x] Select the first M5 multi-gateway topology: PostgreSQL transactional
  outbox and sequence truth, expiring Redis gateway/routes plus bounded
  per-gateway Streams, post-registration/periodic sequence repair, and no
  independent durable broker until worker/backlog evidence justifies it
  (ADR-0348).
- [x] Add the inactive V050 payload-free conversation-event outbox with bounded
  claim/retry/publication state and an unpublished availability index. New V2
  message/entry/reply/mention data commits atomically with one stable event row;
  exact and raced retries create no additional sequence or outbox row, and an
  injected outbox conflict rolls back the message/entry/sequence before the next
  submission reuses that sequence.
- [x] Add V051 fenced outbox ownership and the inactive PostgreSQL relay port:
  bounded `SKIP LOCKED` claims, one unpublished head per conversation, durable
  attempt count, delayed retry, expired-lease reclamation, and exact
  owner/token/expiry completion. Real PostgreSQL rejects stale and wrong-owner
  completion and releases the next sequence only after its predecessor is
  published (ADR-0349).
- [x] Add a scheduler-neutral bounded relay pass with strict claim-batch
  validation, fixed publication outcomes, redacted unexpected failures,
  exponential retry capped at five minutes, fenced ownership-loss accounting,
  and fixed-cardinality run results. It remains uncomposed and does not affect
  the local live path (ADR-0350).
- [x] Add an identity-free PostgreSQL outbox status snapshot and fixed Prometheus
  gauge renderer for unpublished/ready/leased/delayed/retried counts, maximum
  attempt count, and oldest backlog age. Real lifecycle tests prove ready counts
  respect per-conversation blocking; the renderer is not exposed until runtime
  composition is activated.
- [x] Add an explicitly started, single-task relay lifecycle loop that drains a
  full batch immediately, polls healthy/idle state at a bounded interval, backs
  off deferred/lost/failed passes, rejects repeated start, cancels pending work
  on close, and exports only fixed counters/gauges. It remains uncomposed.
- [x] Define the Redis-independent expiring gateway/conversation route and
  bounded target-stream ports. The aggregate publisher refuses incomplete
  target lookup, emits payload-free stable hints to every target, retries any
  partial dependency failure, and treats an empty complete route set as durable
  success (ADR-0351).
- [x] Add an uncomposed standalone Redis adapter using Lettuce 7.6 with required
  production TLS/authentication, bounded command timeout/request queue,
  lease-conditional Lua route publication, lazy stale-route cleanup, and exact
  bounded payload-free Streams. An isolated real Redis test proves expiry,
  reconnect, and 100-entry trimming; TLS/ACL capability remains an activation
  gate (ADR-0352).
- [x] Add bounded per-boot-gateway stream consumption with strict three-field
  payload-free parsing, opaque Redis cursor paging, and empty-tail stability.
  Real Redis proves exact-trimmed 51–150 retention and 60/40 reads; stream
  position is explicitly non-durable and product consumption still requires
  local subscription matching plus sequence repair (ADR-0353).
- [x] Add the application route-registration service with a random boot lease,
  explicit 5–60 second bounds, catch-up-before-route ordering, mandatory second
  authoritative repair, monotonic progress validation, and fail-closed route
  removal after repair failure. Gateway reference counting/renewal composition
  remains default-off (ADR-0354).
- [x] Add a default-uncomposed boot-lease renewal loop: immediate first renewal,
  healthy interval no longer than half the 5–60 second lease, bounded fast retry
  before expiry, explicit `leaseValid=false` at expiry, fixed counters, repeated
  start rejection, and pending-task cancellation on close.
- [x] Add the scheduler-neutral Redis hint consumer pass: strict current-boot
  target validation, bounded ordered applied/duplicate/not-subscribed outcomes,
  and stop-at-first-repair-failure cursor retention so retries never skip a
  failed hint. The local authorized repair port remains the next slice
  (ADR-0355).
- [x] Implement message-only local hint repair against authorized PostgreSQL
  history: per-channel observed sequence, server-bound account lookup, exact
  stable message ID/sequence matching, existing capability filtering, duplicate
  suppression, membership-revocation route cleanup, and fail-closed conflict.
  The adapter remains uncomposed and mixed event kinds remain out of scope until
  they gain atomic outbox writers (ADR-0356).
- [x] Add the default-uncomposed hint consumer lifecycle with strictly increasing
  unsigned Redis IDs, immediate full-batch drain, bounded idle polling,
  exponential repair-failure retry without failed-cursor advancement, clean
  shutdown, and fixed identity-free lease/consumer metrics (ADR-0357).
- [x] Add a default-off real PostgreSQL/Redis two-gateway proof: one atomic
  message/outbox commit reaches both boot-specific streams and both authorized
  local connections, while repeated hints produce no duplicate socket output.
  The product listener, TLS/ACL, dependency-loss, and load-balancer gates remain
  pending (ADR-0358).
- [x] Add one default-off owner for the route-lease, hint-consumer, and relay
  lifecycles plus their scheduler, gateway release, and Redis adapter. It gates
  readiness on the live boot lease and provides ordered partial-start rollback
  and bounded shutdown without constructing the product graph (ADR-0359).
- [x] Move process-local live-router ownership to the Java composition root and
  inject the same instance into the product WebSocket server, establishing the
  shared subscription boundary required by future Redis hint repair without
  activating distributed routing (ADR-0360).
- [x] Add a strict default-off distributed-routing configuration boundary with
  production Redis TLS/authentication, secret redaction, bounded command/queue
  resources, and explicit loopback-only plaintext test mode. The Redis module
  is now runtime-linkable but remains unconstructed (ADR-0361).
- [x] Add the single default-off component factory that shares one Redis adapter
  across routes/Streams, composes PostgreSQL outbox and authoritative local
  repair, owns bounded named scheduling, exposes fixed telemetry, and cleans all
  partial construction failures. `GatewayRuntime` still does not call it
  (ADR-0362).
- [x] Add post-history-response external route activation and a distributed
  local-router decorator: flush history first, publish the route, perform up to
  ten bounded PostgreSQL repair pages with server-bound authorization, roll back
  failed activation, and remove the route after the last subscriber. Periodic
  conversation-route renewal remains required before composition (ADR-0363).
- [x] Renew every active conversation route with the gateway boot lease at a
  10-second interval inside its 30-second expiry, carrying the maximum locally
  observed sequence and failing future distributed readiness closed if any
  renewal fails. Empty/unsubscribed snapshots produce no route writes
  (ADR-0364).
- [x] Compose the distributed graph into `GatewayRuntime` behind its default-off
  flag: inject the post-response router, start routing before product admission,
  gate readiness on PostgreSQL plus Redis lease validity, drain product sockets
  before route teardown, and expose fixed routing/outbox metrics. Real
  PostgreSQL+Redis+TLS/WSS proves exactly-once-visible delivery across the
  Redis-first/local-publish race; production TLS/ACL and failure gates remain
  pending (ADR-0365).
- [x] Add a disposable TLS-only Redis capability gate with an ephemeral CA,
  hostname verification, disabled default user, `chat:v2:*` key scope, and only
  the routing commands required at runtime. Real Lettuce operations prove the
  route/Stream path works while an out-of-scope key, wrong ACL password, and
  certificate hostname mismatch fail closed without credential disclosure.
  Redis dependency-loss/recovery and rolling multi-gateway gates remain
  pending (ADR-0366).
- [x] Make the distributed route lease an explicit bounded runtime policy:
  retain the 30-second lease/10-second renewal default, accept only 5–60 seconds,
  derive a half-life-safe renewal interval, and cap retry delay inside the
  selected lease. This enables bounded failure drills without weakening the
  default or allowing invalid lease timing (ADR-0367).
- [x] Pass a real product-runtime Redis dependency-loss gate: keep authenticated
  TLS/WSS sessions alive, stop Redis until the five-second route lease withdraws
  readiness while liveness remains healthy, commit and locally deliver one
  PostgreSQL-backed message during the outage, restart an empty Redis at the
  same endpoint, rebuild routing, restore readiness, publish the durable outbox,
  and emit no duplicate client event. Multi-gateway loss/load-balancer and
  rolling-deployment gates remain pending (ADR-0368).
- [x] Pass the first real two-product-gateway delivery gate: connect the sender
  only to gateway A and the caught-up receiver only to gateway B, commit one
  message/outbox row through A, route a payload-free Redis hint to B, reauthorize
  and load the exact body from PostgreSQL, and emit one WSS event with B's hint-
  applied telemetry proving the cross-process path. Gateway removal/reconnect
  and load-balancer behavior remain pending (ADR-0369).
- [x] Pass a cooperative rolling-topology rehearsal: deliver sequence 1 from A
  to a receiver held on B, let the sending client complete WebSocket close, drain
  and remove A, keep B ready and connected, start replacement C, reconnect the
  same device identity, repair sequence 1 from PostgreSQL history, then deliver
  sequence 2 from C to B exactly once with two published outbox rows. Real load-
  balancer propagation, version skew, crash loss, and forced-drain timeout remain
  pending (ADR-0370).
- [x] Add the load-balancer readiness foundation without exposing the loopback
  admin plane: serve a minimal dynamic `GET`/`HEAD /health/ready` on the existing
  TLS product listener after Host/proxy policy, prove 200→503→200 through a real
  Redis outage, and generate a bounded HAProxy 3.2 least-connections policy with
  verified backend TLS, overwritten forwarding headers, and active product-port
  checks. Unit/injection tests and pinned-container syntax pass; real proxy
  traffic and deregistration remain pending (ADR-0371).
- [x] Pass a real HAProxy withdrawal gate: place two authenticated WSS sessions
  on different complete gateways through least-connections, withdraw one
  gateway from active TLS health checks while its existing session drains,
  route a replacement session to the stable gateway, deliver sequence 1 during
  drain, repair it from PostgreSQL after reconnect, then deliver sequence 2
  exactly once with two durable messages and published outbox rows. Abrupt
  crash, mixed-version, reload, and reconnect-storm evidence remain pending
  (ADR-0372).
- [x] Pass an abrupt process-loss gate with two independent gateway JVMs: commit
  and deliver sequence 1 across two HAProxy-placed sessions, force-kill the
  sender JVM without shutdown cleanup, let HAProxy remove its dead port and its
  five-second Redis route expire, reconnect through the survivor, repair
  sequence 1 from PostgreSQL, and deliver sequence 2 exactly once. Correlated
  failure, mixed-version, reload, and reconnect-storm capacity remain pending
  (ADR-0373).
- [x] Add a bounded post-crash reconnect measurement: distribute twelve WSS
  sessions evenly across two independent gateways through HAProxy, force-kill
  one JVM, and resume its six sessions on the survivor in three batches of two
  scheduled 100 ms apart. Versioned JSON records exact identity rotation,
  latency, arrival jitter, successes/errors, environment, revision, and dirty
  state; a strict validator gates clean comparable evidence. This bounded local
  curve recorded 6/6 successful resumes, 29.745/44.200 ms P50/P95, and 10.043
  ms maximum scheduling jitter on the documented Mac development host; it is
  not a production fleet rate (ADR-0374).
- [x] Pass the forced-drain branch through the real edge: hold an authenticated
  WSS session open, withdraw its gateway from admission, require the configured
  one-second drain to wait at least 900 ms but finish inside three seconds,
  observe forced client termination, then resume the exact durable session
  through HAProxy on the still-connected surviving gateway. The production
  default remains 15 seconds (ADR-0375).
- [x] Pass a real HAProxy master-worker reload: atomically replace the two-
  gateway configuration with the one gateway not holding the sender, start a
  new worker, stop old-worker admission while its WSS tunnel remains active,
  deliver sequence 1 through that old tunnel, place a new session only on the
  retained gateway, repair history, and deliver sequence 2 without duplication.
  Certificate rotation and mixed-version rollout remain separate gates
  (ADR-0376).
- [x] Rotate the HAProxy frontend leaf/keypair through the same master-worker
  path: verify exact SHA-256 fingerprints before and after reload using the real
  HTTPS/ALPN product stack, preserve the former worker's authenticated WSS long
  enough to deliver sequence 1, and deliver sequence 2 through a new connection
  presenting the replacement certificate. Backend gateway CA rotation remains
  a separate trust-migration gate (ADR-0377).
- [x] Rotate private HAProxy-to-gateway trust with expand-migrate-contract:
  prove old-only trust rejects the new gateway, the overlap bundle accepts both
  certificate generations, and contracted new-only trust rejects the old
  certificate while admitting the new gateway. Former-worker WSS traffic
  remains ordered across both reloads.
  Multi-edge secret distribution remains an operations gap (ADR-0378).
- [x] Add an immutable gateway release-identity boundary for rolling evidence:
  strictly pair canonical SemVer with exact source revision, bind protocol
  version to the running V2 module, carry an ADR-governed compatibility epoch,
  and expose deterministic JSON only on loopback `GET /identity`. A two-artifact
  mixed-version runtime gate remains pending (ADR-0379).
- [x] Pass a two-artifact rolling-compatibility gate: independently export and
  build pinned previous revision `1487e1f...` and candidate `79ed828...`, verify
  each running loopback identity and their real metrics-surface difference,
  deliver sequences 1 and 2 bidirectionally across versions, remove the previous
  JVM through HAProxy health, repair history on the candidate, and deliver
  sequence 3. Every future release pair must rerun the gate (ADR-0380).
- [x] Treat the edge as a separate failure domain: run two independent HAProxy
  containers and two gateways, deliver sequence 1 across both edge and gateway
  boundaries, force-kill only the primary edge, preserve gateway readiness,
  explicitly resume the durable device session through the secondary edge,
  repair history, and deliver sequence 2 without duplication. DNS/GSLB and
  automatic client endpoint failover remain pending (ADR-0381).
- [x] Add bounded automatic edge selection to the default-off Web V2 preview:
  validate one primary plus at most three immutable unique WSS endpoints,
  rotate on socket construction/close under the existing jittered backoff,
  preserve memory-only session resume, and keep browser-offline transitions
  outside the rotation budget. Windows parity and production discovery remain
  pending (ADR-0382).
- [x] Add the matching bounded Windows V2 edge selection: validate one required
  compiled primary and one optional distinct fallback at CMake and runtime
  boundaries, rotate after connection failure under the existing jittered
  reconnect policy, preserve memory-only session resume, and expose diagnostic
  schema 2 for native Windows evidence. Dynamic discovery and signed clean-host
  product proof remain pending (ADR-0383).
- [x] Publish the Web/Windows edge-failover behavior contract: trusted immutable
  configuration, rotation events, offline distinction, jitter/backoff, fresh
  negotiation, memory-only resume, evidence ownership, and rollback are now
  explicit. Product multi-host and reconnect-capacity evidence remain pending.
- [x] Prove Web V2 rotates away from a WSS entry that fails the fixed
  subprotocol or connection phase timeout, retaining bounded jitter before the
  fallback attempt. This is handshake-failure evidence, not generic application
  health or degraded-edge discovery.
- [x] Add a bounded dual-edge reconnect measurement: hold twelve sessions on a
  primary edge and six on secondary, kill only primary HAProxy, resume all
  twelve through secondary in four batches of three at 100ms intervals, and
  strictly record identity reconciliation, latency, scheduling jitter,
  environment, revision, and dirty state (ADR-0384).
- [x] Record and independently validate the clean exact-revision dual-edge
  baseline at `ad97e070...`: 12/12 resumes, zero errors, 21.696/37.212 ms
  P50/P95, 8.503 ms maximum scheduling jitter, and 36.132 controlled resumes/s.
  This local curve is not production saturation or fleet capacity evidence.
- [x] Export fixed-name authentication worker active/queue gauges from the
  runnable gateway's bounded executor on the loopback metrics endpoint, without
  identity labels or readiness side effects. In-window peak sampling and wider
  database/event-loop/resource saturation remain pending (ADR-0385).
- [x] Upgrade new bounded dual-edge reconnect evidence to schema version 2 and
  sample authentication worker/queue peaks every five milliseconds across the
  recovery window. The clean exact-revision baseline at `4d9574f8...` completed
  12/12 resumes with 38 samples, maximum observed active workers 1, queue peak
  0, and 21.804/32.788 ms P50/P95 latency. Historical schema version 1 remains
  valid; wider resource signals and the saturation knee remain pending
  (ADR-0386).
- [x] Export fixed-name active, idle, total, maximum, and waiting-thread gauges
  for the gateway-owned PostgreSQL pool, including explicit management-view
  availability without issuing a database query or changing readiness. Window-
  bound reconnect sampling and other resource signals remain pending
  (ADR-0387).
- [x] Upgrade new reconnect evidence to schema version 3 and sample PostgreSQL
  pool availability, active/total connections, and waiting threads in the same
  target five-millisecond window as authentication saturation. Historical
  schemas remain valid. The clean baseline at `b46768e5...` completed 12/12
  resumes with 68 shared samples, authentication active/queue peaks 3/0, and
  PostgreSQL active/total/waiting peaks 1/3/1 against a four-connection maximum.
  Wider resource signals and a saturation knee remain pending (ADR-0388).
- [x] Add lifecycle-bound 50 ms probes to every owned Netty worker event loop
  and export fixed-name latest/since-start maximum lag, sample count, worker
  count, pending tasks, and explicit availability. Reconnect-window event-loop
  evidence and CPU/memory signals remain pending (ADR-0389).
- [x] Upgrade new dual-edge reconnect evidence to schema version 4, reconcile
  Netty probe progress across the window, and record event-loop availability,
  latest-lag, since-start-lag, and pending-task observations alongside worker
  and PostgreSQL pressure. The clean baseline at `14e03b67...` completed 12/12
  resumes with 68 shared samples, 28 advancing probe samples, 2.759 ms maximum
  latest lag, no pending tasks, and an unchanged 24.897 ms since-start maximum.
  CPU/memory signals and a saturation knee remain pending (ADR-0390).
- [x] Export portable fixed-name cumulative process CPU time, CPU-time
  availability, JVM heap used/committed/maximum, uptime, and available
  processors from Java standard management APIs without platform-native
  dependencies. Window evidence, RSS, GC pauses, and a saturation knee remain
  pending (ADR-0391).
- [x] Upgrade new dual-edge evidence to schema version 5 and record portable
  process CPU-time/uptime deltas plus JVM heap used endpoints/peak,
  committed endpoints, effective maximum, and processors in the same shared
  window. The clean baseline at `841b9680...` completed 12/12 resumes and
  observed 314.183 ms gateway CPU time across 334 ms uptime, heap used
  304-to-318 MiB, and a 318 MiB peak. RSS, GC pauses, repeated workload steps,
  and a saturation knee remain pending (ADR-0392).
- [x] Replace the single new-evidence point with a schema-6 fixed workload
  ladder: `step-12`, `step-24`, and `step-48` retain six survivor sessions,
  four 100 ms-spaced batches, strict topology/resource reconciliation, and the
  non-capacity warning while increasing each batch from 3 to 6 to 12 resumes.
  Historical schemas 1 through 5 remain valid; repeated clean runs and a
  pressure-onset result remain pending (ADR-0393).
- [x] Add a self-contained repeated ladder contract: run each fixed profile
  three times in a fresh disposable environment, retain all nine schema-6 child
  records, require shared revision/host identity, and apply versioned majority
  pressure plus relative-and-absolute latency-candidate rules. The conclusion
  is explicitly a local diagnostic, never safe capacity or an SLO. A clean
  commit-exact ladder result remains pending (ADR-0394).
- [x] Record the clean exact-revision three-by-three ladder at `af020e6c...`.
  All nine real scenarios converged with zero reconnect errors. Median P95 rose
  from 42.011 to 47.650 to 68.626 ms; direct pressure signals appeared in 1/3,
  2/3, and 3/3 runs, so the strict local conclusion is first repeated pressure
  at `step-24`. Authentication queues stayed empty, normalized CPU remained
  below 0.171, and no latency-knee candidate was declared. Signal duration,
  RSS, GC pauses, isolated-host evidence, and production capacity remain
  unproven.
- [x] Upgrade new raw reconnect evidence to schema version 7 and count positive
  samples plus longest consecutive streaks for authentication queue,
  PostgreSQL waiters, and Netty pending tasks in the existing shared 5 ms
  window. Strict validation reconciles peaks, counts, streaks, and sample totals;
  uniform schema-6 ladder history remains valid while mixed child schemas are
  rejected. Duration-aware aggregate classification remains pending (ADR-0395).
- [x] Upgrade new repeated ladder evidence to aggregate schema version 2 and
  separate diagnostic peaks from sustained signals. Queue/waiter/pending work
  now requires at least two consecutive positive samples, while the existing
  50 ms event-loop-lag and 0.8 normalized-CPU rules remain. Two of three runs
  are still required, and historical schema-1/schema-6 aggregates reproduce
  their original conclusion (ADR-0396).
- [x] Record the clean exact-revision duration-aware ladder at `f5400819...`.
  All nine scenarios converged. Peak signals appeared in 2/3, 2/3, and 3/3
  runs, but sustained signals appeared in 0/3, 1/3, and 0/3; no profile met the
  majority rule. Median P95 was 42.375, 43.137, and 78.394 ms, below the 2x
  latency-candidate ratio. The strict conclusion is no sustained-pressure knee
  observed within this local ladder, not safe production capacity. Gateway-child
  RSS and GC pause evidence remain pending.
- [x] Export portable fixed-name JVM GC availability, cumulative collection
  count, and cumulative collection elapsed seconds from standard management
  beans. Undefined values fail closed as unavailable/zero and do not affect
  readiness. Collection time is explicitly not labeled stop-the-world pause;
  reconnect-window deltas, exact pause evidence, and RSS remain pending
  (ADR-0397).
- [x] Upgrade new raw reconnect evidence to schema version 8 and reconcile JVM
  GC collection count/time before, after, and delta in the shared sampling
  window. New nine-run aggregates use schema version 3 and retain per-run GC
  deltas; aggregate schemas 1/2 remain paired with raw schemas 6/7. A real
  `step-12` run observed 68 available samples and zero collection count/time
  delta. Exact pauses and RSS remain pending (ADR-0398).
- [x] Record the clean exact-revision GC-aware schema-3 ladder at `7e9e7ba0...`.
  All nine scenarios converged and no sustained or latency knee triggered.
  Seven runs had zero GC delta; one `step-12` run observed one collection/1 ms
  collection time and one `step-24` run observed one collection/3 ms. No
  `step-48` run collected. Collection activity does not explain the profile
  curve; exact pauses, RSS, and production capacity remain unproven.
- [x] Define the process RSS provider boundary: resident/working-set bytes,
  fixed availability/bytes/age/failure metrics, a lifecycle-owned cached sampler
  no faster than 250 ms, dependency-free Linux `/proc` first, and explicit
  native-review gates for macOS development evidence or future Windows Java
  server support. Product Windows client support remains separate from server
  deployment hosts. Implementation and clean RSS-aware evidence remain pending
  (ADR-0399).
- [x] Implement the RSS provider foundation: strict bounded Linux
  `/proc/self/status` `VmRSS` parsing with overflow-safe KiB conversion,
  current-platform selection, explicit unavailable fallback, a minimum 250 ms
  daemon sampler, cached availability/bytes/age/failure snapshots, recovery
  after read failure, and lifecycle shutdown. Loopback metric composition and
  Linux-host integration evidence remain pending (ADR-0399).
- [x] Compose the cached RSS snapshot into the loopback endpoint with fixed
  availability, resident bytes, sample age, and read-failure metrics. The
  sampler is owned and closed by `GatewayRuntime`, partial construction closes
  it, readiness is unchanged, and unsupported macOS reports unavailable/zero
  without shell commands. Linux-host available-metric and reconnect-window
  evidence remain pending (ADR-0399).
- [x] Upgrade new dual-edge reconnect evidence to raw schema 9 and repeated
  aggregates to schema 4. Record cached RSS availability, the configured 250 ms
  refresh, bytes before/after/maximum, maximum sample age, and read-failure
  movement without treating repeated cache observations as native samples.
  Fully unavailable macOS windows remain valid and explicitly unmeasured;
  positive Linux-host evidence remains pending (ADR-0400).
- [x] Retain a clean aggregate-schema-4 ladder at exact revision `5c50a0b...`.
  All nine dual-edge scenarios completed; no sustained-pressure or latency knee
  triggered. Every macOS child reported zero available RSS samples and zero
  byte fields, proving schema compatibility and honest unavailability rather
  than process-memory capacity. Positive Linux-host RSS evidence remains
  pending.
- [x] Add a Linux-only native provider integration gate to the ordinary gateway
  test task. Linux must select `/proc/self/status` and produce a positive,
  available, failure-free first cached snapshot; macOS skips the host-specific
  assertion. The gate passed with one test, zero skips, and zero failures in the
  digest-pinned Gradle 8.14.3/JDK 21 Linux image. Full Linux schema-9 reconnect
  ladder evidence remains pending.
- [x] Automate the pinned Linux provider gate for POSIX development hosts with
  a project-scoped Docker-native Gradle cache volume, non-root test execution,
  and disposable containers. This avoids Docker Desktop bind-mount execute
  permission drift and repeated Maven availability coupling while keeping the
  gate reproducible from the macOS workstation.
- [x] Expose the pinned Linux provider verification through the unified M0
  runner as explicit `--linux-rss`. Keep it outside `--all` because it requires
  Docker and may pull the pinned image/dependencies on first use.
- [x] Expose portable fixed-name direct-buffer availability, count, estimated
  used bytes, and total capacity from Java's standard buffer-pool MXBean. Keep
  this separate from heap and RSS, and do not label it as complete native
  memory or a capacity threshold (ADR-0401).
- [x] Upgrade new dual-edge reconnect evidence to raw schema 10 and repeated
  aggregates to schema 5. Record direct-buffer availability plus
  before/after/maximum count, estimated used bytes, and total capacity in the
  shared observation window without defining a capacity threshold (ADR-0402).
- [x] Retain a clean aggregate-schema-5 ladder at exact revision `0c50614...`.
  All nine dual-edge scenarios completed with direct-buffer metrics available
  in every sample; per-run maximum estimated used memory was about 8.62–10.12
  MiB. No sustained-pressure or latency knee triggered. RSS remained unavailable
  on macOS, and this is not a leak, memory limit, or capacity result.

Engineering status: complete (ADR-0403). Distributed routing remains
default-off until an environment-specific release and operations gate passes.

Exit evidence:

- [x] Losing one gateway does not lose committed messages: ADR-0373 force-kills
  a gateway after commit and repairs the durable sequence through the survivor.
- [x] Users connected to different gateways exchange messages correctly:
  ADR-0369 exercises the real PostgreSQL/Redis/TLS-WSS cross-process path.
- [x] Duplicate event delivery remains safe: ADR-0358 and ADR-0365 prove
  repeated and racing hints do not create duplicate visible delivery.
- [x] Failure drills and repeated load diagnostics document the bounded
  engineering envelope: ADR-0368 and ADR-0370 through ADR-0402 cover dependency
  loss, rolling/crash/edge behavior, reconnect load, and resource signals. They
  are not a production capacity or SLO claim.

Evidence-triggered follow-ons, not M5 exit blockers:

- introduce an independent durable broker only if sustained relay backlog or
  asynchronous-worker evidence justifies a second operational dependency;
- add database partitioning/read replicas only after production query evidence;
- isolate push, thumbnail, scanning, retention, audit, or analytics workers as
  their owning M6 feature slices require them;
- retain per-conversation ordering as the outbox, routing, and repair partition
  boundary unless a later ADR changes it.

## M6 — Modern Product Capabilities

Goal: grow features on top of reliable foundations.

Progress:

- [x] Deliver user-managed multi-device login and device management on V2 Web
  and Windows clients.
  - [x] Define ADR-0321 and add V043 immutable actor/session-bound device
    revocation audit with same-account and non-self constraints.
  - [x] Implement the bounded active-device directory and serializable
    other-device revocation boundary with durable admission, all-session
    invalidation, exact retry, mutual-revocation convergence, and restart proof.
  - [x] Allocate permanent V2 message types 130--133 with bounded payload policy
    and Java/C++/TypeScript golden-wire compatibility.
  - [x] Add the authenticated bounded V2 gateway handler and process-local
    post-commit target disconnect index; runtime composition remains detached.
  - [x] Compose the handler, PostgreSQL adapter, fixed-cardinality telemetry,
    process-local connection index, and bounded worker ownership in the product
    gateway runtime.
  - [x] Extend the default-off Web V2 protocol client with correlated bounded
    device listing/revocation commands, defensive response validation, and
    current-device refusal. Application coordinator and UI remain pending.
  - [x] Add the default-off Web device-management user path with automatic
    post-auth/reconnect refresh, no durable security-state cache, ambiguous
    disconnect recovery, current-device protection, accessible confirmation,
    opaque failure/retry behavior, and offline mutation disablement.
  - [x] Add a detached Windows Qt device-management ViewModel and accessible
    Widgets dialog with request correlation, stale-response containment,
    disconnect ambiguity recovery, server-projection validation,
    current-device protection, and explicit destructive confirmation. The
    generated-C++ V2 WSS transport adapter and product composition remain.
  - [x] Publish reviewed Windows C++ V2 bindings from the authoritative Proto
    task, fail regeneration on stale committed bytes, and compile that exact
    tree in the pinned three-language golden gate (ADR-0322).
  - [x] Add a transport-independent Windows device-management protocol client
    over the reviewed C++ bindings with authenticated session/request binding,
    bounded in-flight work, defensive server-projection validation, current-
    device protection, positive wire timestamps, and disconnect state
    abandonment.
  - [x] Add the pure C++ Windows V2 exact-version handshake, fresh
    authentication, memory-only resume, session-authority validation, and
    authenticated device-codec composition; the Qt WSS lifecycle remains.
  - [x] Add the detached Qt WSS lifecycle for the exact Windows endpoint and
    `chat.v2` binary subprotocol with phase timeouts, memory-only resume,
    bounded jittered reconnect, fail-closed framing, and Qt device projection.
  - [x] Compile the reviewed V2 bindings and detached Windows WSS/session/device
    stack into the canonical CMake product with checksum-pinned, static
    Protobuf/Abseil and no new installer runtime DLL (ADR-0323).
  - [x] Add fail-closed, default-off Windows V2 product configuration for one
    exact compiled `wss://authority/v2/windows` endpoint (ADR-0324).
  - [x] Add a transport-independent Windows application service with one-use,
    at-most-60-second in-memory credential handoff, post-auth directory refresh,
    disconnect abandonment, and no durable security-state cache (ADR-0325).
  - [x] Add an owner-only, atomic, fail-closed Windows installation device UUID
    independent of accounts, cache, update rollout, and sessions (ADR-0326).
  - [x] Compose the supported Windows application/UI path behind the default-off
    product gate: V1-login one-use handoff, controller-owned V2 session, automatic
    live refresh, hidden-by-default settings entry, and logout teardown
    (ADR-0327). Web remains behind its existing V2 preview/cutover gate.
- [x] Deliver reply and quote behavior on V2 Web and Windows clients.
  - [x] Allocate the distinct `SubmitReplyMessage` command and an additive,
    server-authoritative reply reference on message records, with bounded
    Java/C++/TypeScript golden-wire compatibility (ADR-0328).
  - [x] Persist immutable same-conversation reply identity in PostgreSQL V044;
    validate live targets under the conversation write lock, preserve exact
    idempotent retries, and compose Java gateway/history/live projection
    (ADR-0329).
  - [x] Extend the Web V2 protocol/transport boundary with type-105 reply
    submission and fail-closed server reply-reference validation.
  - [x] Add Web optimistic/offline reply composition, IndexedDB-safe target
    identity, retry/replay, authoritative merge, recalled/unavailable rendering,
    keyboard operation, and accessible cancellation (ADR-0330).
  - [x] Add a detached Windows V2 messaging protocol boundary for ordinary and
    reply submissions, ACK correlation, ordered history/live projections,
    reply-reference validation, and disconnect abandonment (ADR-0331).
  - [x] Add the isolated Windows V2 SQLite message store with account isolation,
    atomic history-page cursors, gap-safe live merges, bounded
    optimistic/accepted rows, exact reply identity, ACK reconciliation,
    explicit retry state, and no copied quote body (ADR-0332).
  - [x] Add detached Windows offline reply orchestration with
    persist-before-send, same-ID reconnect replay, bounded in-flight work,
    retryable deferral, explicit permanent-failure retry, ACK reconciliation,
    and cursor-based history repair (ADR-0333).
  - [x] Project ordered recall/deletion mutations into Windows SQLite before
    cursor commit, erase recalled target bodies, evict deleted targets, and
    refuse new replies to recalled messages (ADR-0332/ADR-0333).
  - [x] Add the detached Windows reply ViewModel with cached-first rows,
    explicit normal/recalled/unavailable reference presentation, target
    selection/cancellation, focus intent, optimistic send, and stable-ID retry.
  - [x] Add the detached accessible Windows Widgets reply panel with explicit
    quote/status labels, keyboard-native buttons, composer focus restoration,
    cancellation, retry, and recalled/unavailable presentation.
  - [x] Multiplex bounded Windows messaging commands, correlated responses, and
    live message events over the existing authenticated product WSS while
    preserving strict device-protocol isolation and fail-closed routing
    (ADR-0334).
  - [x] Compose the authenticated Windows messaging runtime around the
    account-isolated SQLite repository, offline application service, ViewModel,
    and shared product transport; abandon only volatile correlations on
    disconnect and fail the socket closed on codec corruption (ADR-0335).
  - [x] Add a strict Windows V2 conversation-directory codec with bounded
    composite cursor paging, user-facing names/roles/unread sequences, ordered
    response validation, disconnect abandonment, and shared-WSS routing
    (ADR-0336).
  - [x] Compose the directory protocol and user-facing Qt projection inside the
    actual Windows V2 product controller: authenticate once, request directory
    and device state together, expose unread/name/kind/role rows, open cached
    message history by authorized UUID, and preserve offline intent on reconnect
    (ADR-0337).
  - [x] Compile an accessible conversation-directory and reply surface into the
    canonical Windows product, reveal it only after default-off V2
    authentication, select server-authorized hidden identities, render cached
    history and unread state, and retain offline access across disconnect
    (ADR-0338).
- [x] Deliver ordered, idempotent message reactions on V2 Web and Windows.
  - [x] Allocate permanent type-106--108 command/response/event payloads, six
    bounded reaction identities, changed-only sequence semantics, and reaction
    details in the mixed conversation history contract (ADR-0339).
  - [x] Persist exact reaction operations, active state, and changed events in
    PostgreSQL V045 under the authoritative conversation sequence, including
    concurrent retry convergence, no-op cursor stability, and target cleanup.
  - [x] Compose authenticated gateway mutation, capability-filtered history,
    capable-only live fan-out, bounded telemetry, and opaque negative paths.
  - [x] Add an offline-safe IndexedDB projection, stable optimistic operation
    replay, ACK/history/live convergence, bounded accessible controls, and
    aggregate rendering to the default-off Web V2 preview.
  - [x] Add the corresponding local projection, optimistic convergence,
    accessible controls, and aggregate rendering to the Windows V2 preview.
- [x] Deliver bounded, ordered pinned messages on V2 Web and Windows.
  - [x] Define capability negotiation, permanent type allocation, role policy,
    a 50-pin bound, exact idempotency, mixed-sequence ordering, target cleanup,
    body-free projection, and offline cursor rules (ADR-0340).
  - [x] Add the generated wire contract, bounded Java policy, mixed-history pin
    detail, and fixed Java/C++/TypeScript golden compatibility gate, keeping
    activation gated until each client completed its local slice.
  - [x] Persist and route authoritative pin operations, history, and live events.
    - [x] Persist exact manual outcomes, shared current state, changed-only mixed
      history, the 50-pin bound, OWNER/ADMIN group authority, and ordered
      recall/deletion cleanup in PostgreSQL V046.
    - [x] Bind pin actors to authenticated gateway identity, reject commands
      before explicit capability negotiation, map fixed outcomes, filter mixed
      history detail without stalling cursors, and fan out live changes only to
      capable subscribers.
  - [x] Add offline-safe Web and Windows projections and accessible controls.
    - [x] Add the Web IndexedDB projection and bounded outbox, optimistic intent,
      ACK/history/live/reconnect convergence, target cleanup, accessible
      controls, and capability activation.
    - [x] Add the Windows SQLite projection and bounded outbox, optimistic
      intent, stable reconnect replay, ACK/history/live convergence, target
      cleanup, accessible Widgets controls, and capability activation.
- [x] Deliver revision-safe message editing on V2 Web and Windows.
  - [x] Define capability negotiation, permanent type allocation, author/window/
    revision policy, V1 compatibility, ordered mutation semantics, privacy
    cleanup, offline conflict behavior, and staged activation (ADR-0341).
  - [x] Add the generated wire contract and fixed Java/C++/TypeScript golden
    compatibility gate while all runtime/client capability paths remain off.
  - [x] Persist exact edit operations, current revisions, changed-only mixed
    history, retention cleanup, and server-authorized policy in PostgreSQL.
    - [x] Add V047 current revision metadata, digest-bound operation outcomes,
      ordered edit events with privacy erasure, and transport-neutral command/
      result ports while runtime capability paths remain off.
    - [x] Implement serialized PostgreSQL authority with exact concurrent
      replay, author/member/V2-origin/window/revision policy, changed-only
      sequence allocation, and recall/deletion body-erasure integration tests.
    - [x] Project current revision metadata and ordered edit history, retaining
      erased event identities so privacy cleanup cannot stall sequence cursors.
  - [x] Compose authenticated gateway mutation, capability-filtered history,
    capable-only live fan-out, bounded telemetry, and opaque conflict paths.
    - [x] Add the default-off authenticated handler for edit command/ACK,
      permanent failure codes, current revision projection, capable history,
      privacy-erased detail omission, and cursor-safe filtered tails.
    - [x] Compose the PostgreSQL edit port into the runtime, publish changed
      edits only to capable subscribers, add bounded telemetry, and activate
      explicit server negotiation after the full gateway gate passes.
  - [x] Add offline-safe Web and Windows edit overlays/outboxes, explicit
    conflict/rebase UX, edited presentation, accessibility, and activation.
- [ ] Deliver bounded structured message mentions on V2 Web and Windows.
  - [x] Define stable target identity, UTF-8 span policy, capability negotiation,
    membership authority, idempotency/edit behavior, privacy cleanup, offline
    behavior, compatibility, and staged activation (ADR-0342).
  - [x] Add additive generated wire fields and fixed Java/C++/TypeScript golden
    compatibility plus bounded structural policy while runtime paths remain off.
  - [x] Persist and authorize mention metadata with submission, edit, history,
    live delivery, recall/deletion cleanup, and privacy erasure in PostgreSQL and
    the authenticated gateway.
    - [x] Add V048 current/edit-event mention storage, edit-operation mention
      digests, bounded constraints, and transactional recall/deletion erasure
      while preserving old-binary empty-mention writes.
    - [x] Bind active-member authorization and exact idempotency into PostgreSQL
      message submission and project current mention history.
    - [x] Bind exact mention-set idempotency, replacement, and ordered history
      projection into the PostgreSQL message-edit adapter.
    - [x] Compose capable-only gateway command, history/live projection, and
      fixed-cardinality telemetry without activating either client.
  - [ ] Add offline-safe Web and Windows mention composition, local persistence,
    rendering, accessibility, and endpoint-specific capability activation.
    - [x] Add a default-off Web protocol boundary that encodes submission/edit
      mentions and defensively validates capability-gated history, ACK, and live
      UTF-8 spans without activating the Web runtime.
    - [x] Extend Web V2 message and edit-intent records plus the isolated
      IndexedDB sanitizer with bounded mention metadata, authoritative edit
      replacement, deep-copy snapshots, and recall cleanup.
    - [x] Define a capability-gated, active-member-authorized, account-ID-cursor
      participant directory so mention composition uses current display names
      without treating them as identity (ADR-0343).
    - [x] Implement the participant-directory protocol, PostgreSQL adapter, and
      authenticated gateway handler while clients remain unactivated.
      - [x] Allocate types 117/118, add bounded participant payload policy, and
        lock the account-ID cursor across generated Java, TypeScript, and C++
        bindings without registering a runtime handler.
      - [x] Add bounded application page/query/result models and the
        active-member-authorized PostgreSQL query with active-account filtering
        and stable ascending account-ID pagination.
      - [x] Register the capability-gated authenticated gateway handler with
        fixed denial and telemetry behavior.
        - [x] Add a dedicated serialized off-event-loop handler with
          server-bound requester identity, capability/shape checks, fixed
          authorization denial, bounded queue, and fixed-cardinality signals.
        - [x] Compose the handler and PostgreSQL port into the product pipeline
          and prove the real runtime path without activating either client.
    - [x] Add a default-off Web participant-directory command/response boundary
      with capability gating, request correlation, stable-cursor validation,
      bounded records, and transport forwarding.
    - [x] Add conversation-scoped Web participant view state with bounded page
      merging, explicit refresh/load-more, fixed denial feedback, and stale or
      disconnected request abandonment while runtime activation remains off.
    - [x] Thread validated structured mention spans through Web optimistic send,
      reply, edit, persistence/replay, and WebSocket dispatch without activating
      capability 4 or exposing unfinished composition UI.
    - [x] Add a framework-independent Web mention composer model for Unicode
      insertion, edit reconciliation, UTF-8 serialization, stored-span restore,
      and identity-preserving render segmentation.
    - [x] Persist Web mention metadata with authoritative messages and pending
      submission/edit workflows, then add composition, rendering,
      accessibility, reconnect convergence, and Web capability activation.
    - [ ] Add the equivalent Windows protocol, SQLite, composition, rendering,
      accessibility, reconnect convergence, and capability activation gates.
      - [x] Extend the default-off Windows messaging protocol boundary with
        bounded structured mentions across send, reply, edit, history, and live
        events, including strict UTF-8 span and response-correlation checks.
      - [x] Migrate the account-isolated Windows cache to schema 6 with
        normalized message/edit-outbox mention rows, restart-safe replay,
        idempotency checks, authoritative edit replacement, and recall/delete
        cleanup; this preparatory slice originally kept capability 4 off.
      - [x] Carry Windows mentions through the application service across
        optimistic reply/edit staging, reconnect replay, authoritative history
        and live projection, edit conflict/rebase, and correlated outcomes.
      - [x] Expose validated mention spans on Windows message rows and thread
        already-composed spans through ViewModel reply/edit actions while
        existing Widgets continue to supply an empty set.
      - [x] Add a default-off Windows participant-directory protocol client with
        bounded correlation, stable account cursor, strict Unicode/role/order
        validation, and disconnect abandonment.
      - [x] Add a conversation-scoped Windows participant view model with a
        500-row bound, stable account ordering, explicit refresh/load-more,
        stale-response isolation, fixed failure, and disconnect state.
      - [x] Compose the participant protocol and view model into the shared
        authenticated Windows controller and WSS routing boundary, requiring an
        explicit caller while Widgets exposure and capability 4 remain off.
      - [x] Add a framework-independent Windows mention composer with
        surrogate-safe insertion and edit reconciliation, exact Qt UTF-16 to
        protocol UTF-8 conversion, stored-span restore, and identity-preserving
        render segmentation while Widgets exposure remains off.
      - [x] Connect the Windows reply editor to an explicit-load, keyboard-native
        participant picker with bounded paging, fixed accessible states,
        Unicode-safe insertion, edit reconciliation, and structured send while
        keeping the product flag and capability 4 off.
      - [x] Render Windows mentions from stored identity spans with escaped rich
        text and accessible plain text, and restore those same spans into the
        shared inline editor for identity-preserving edits while activation
        remains off.
      - [x] Compose Windows capability 4 into the product source, fail closed on
        incomplete negotiation, exclude the authenticated account from picker
        candidates, abandon failed correlations, and refresh the active member
        projection after session resume.
      - [ ] Pass the activated Windows Release build and Widgets interaction
        gate on a Windows runner before treating the endpoint as releasable.
- [ ] Deliver server-authoritative text message forwarding on V2 Web and Windows.
  - [x] Define single-target retry semantics, source/destination authority,
    edit-race behavior, privacy-safe projection, compatibility, migration, and
    staged activation (ADR-0344).
  - [x] Allocate default-off capability 5 and command type 119, add the
    destination `forwarded` marker, bounded structural policy, generated
    Java/TypeScript/C++ bindings, and a fixed three-language command fixture.
  - [x] Persist and authorize exact forwarding in PostgreSQL, then compose the
    default-off authenticated gateway handler, history/live projection,
    privacy-safe telemetry, and capability filtering.
    - [x] Add V049 destination markers and digest-only forward outcomes plus a
      transport-neutral command/result port and transactional PostgreSQL
      authority for source read, destination write, exact retry, revision race,
      current-text copy, and privacy-stripped history projection.
    - [x] Register the default-off capability-gated handler, capable history and
      live marker projection, bounded execution, and fixed-cardinality signals.
  - [x] Add offline-safe Web and Windows forward outboxes, destination picker,
    presentation, accessibility, reconnect convergence, and endpoint-specific
    activation gates.
    - [x] Add a default-off Web protocol and transport boundary with capability
      gating, bounded command construction, stable idempotency correlation, and
      defensive validation of the additive forwarded marker.
    - [x] Extend the isolated Web V2 IndexedDB message model with the forwarded
      marker and a validated local-only source pointer for unresolved commands;
      confirmed projections discard that pointer.
    - [x] Add the default-off Web application forward outbox with target-cache
      hydration, persist-before-send, stable retry identity, optimistic target
      presentation, and ACK-lost convergence through authoritative history.
    - [x] Add a default-off accessible Web target-conversation dialog,
      server-authority/privacy copy, forwarded presentation, retry feedback,
      and an application-level activation guard.
    - [x] Add the default-off Windows C++ type-119 protocol boundary with
      bounded command construction, stable acceptance correlation, and
      defensive capability-gated forwarded-marker projection.
    - [x] Migrate the isolated Windows V2 SQLite store to retain the forwarded
      marker and unresolved local source triple, enforce retry equality across
      restart, and erase private source identity on acceptance.
    - [x] Add the default-off Windows application-service forward path with
      source availability checks, persist-before-send, reconnect replay,
      stable acceptance, and authoritative marker projection.
    - [x] Project the privacy-safe Windows forwarded marker through the detached
      ViewModel and accessible Widgets message row without exposing source
      identity or enabling forwarding authoring.
    - [x] Add a default-off accessible Windows single-target dialog populated
      only from the authorized conversation-directory snapshot, excluding the
      source conversation and failing closed without source context.
    - [x] Compose the detached Windows forward action through the ViewModel,
      authorized-directory picker, and Widgets row with an independent
      default-off UI guard; product controller and capability activation remain off.
    - [x] Add a bounded process-local per-account forwarding admission port,
      strict runtime limits, retryable fixed protocol rejection, and a
      fixed-label counter before capability activation.
    - [x] Lock old-client compatibility for forwarded history and live events:
      legacy peers receive ordinary copied text with identical sequence/cursor
      progress while only capability-5 peers receive the marker.
    - [x] Add a default-deny gateway handshake policy seam that negotiates
      capability 5 only when both an explicit server policy and the client
      request it; product runtime policy remains off.
    - [x] Thread the strict default-false
      `CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED` product setting through the
      gateway composition root and WebSocket upgrade into that handshake seam.
    - [x] Add the strict default-off Web build gate that drives protocol
      capability request and application/UI activation from one validated value.
    - [x] Add the independent default-off Windows CMake forwarding gate, require
      the V2 preview build, and expose one immutable product-configuration value
      without changing runtime negotiation yet.
    - [x] Add the Windows session/transport capability-5 negotiation seam:
      default construction requests exactly capabilities 1–4, while enabled
      construction requests and requires the ordered fifth capability.
    - [x] Carry the single Windows product gate through the session transport,
      controllers, application service, ViewModel, and Widgets dialog; reject
      type 119 in the default transport and expose forwarding only after the
      enabled capability path is composed.
    - [x] Document and lock the cross-endpoint activation contract, complete the
      Java forwarding configuration reference, and require gateway-first
      activation with client-first rollback and retained release evidence.
    - [x] Add non-published CI compile gates for a forwarding-enabled Web preview
      and Windows Release client, retaining the ordinary default-off artifacts
      and running the Windows target-dialog interaction test separately.
    - [x] Add a side-effect-free Windows binary configuration diagnostic and
      require CI to prove the final ordinary executable is default-off while the
      isolated forwarding candidate contains the exact enabled configuration.
  - [ ] Pass the forwarding-enabled workflow on a Windows runner, retain the
    final binary diagnostic and Widgets evidence, and complete endpoint canary
    plus rollback rehearsal before treating forwarding as releasable.
- [ ] Deliver server-authoritative conversation message search on V2 Web and
  Windows.
  - [x] Define capability 6, message types 126/127, literal UTF-8 matching,
    descending sequence pagination, current-state mutation behavior,
    PostgreSQL authority, rebuildable-index boundaries, and staged activation
    (ADR-0404).
  - [x] Add the generated wire contract and bounded application query model
    while runtime and both clients remain off.
  - [x] Implement the authorized PostgreSQL query, deletion/recall/edit
    semantics, query-plan evidence, and gateway handler behind a default-off
    server policy.
    - [x] Add the repeatable-read PostgreSQL adapter with active-member and
      group-lifecycle authorization, literal current-text matching, descending
      sequence pages, edit replacement, recall/deletion/non-text exclusion, and
      existing history-index eligibility evidence. Runtime remains uncomposed.
    - [x] Add the detached capability-gated authenticated search handler with
      server-bound account identity, bounded serialized work, defensive result
      projection, feature-metadata filtering, and fixed failure/telemetry paths.
    - [x] Make capability 6 negotiation strictly default-off and available only
      to an explicitly constructed candidate handshake policy.
    - [x] Add default-off runtime configuration, compose the PostgreSQL handler
      with fixed-cardinality telemetry, and pass the real TLS/WSS authorization
      gate.
  - [ ] Add cache-aware, accessible Web and Windows search surfaces with
    endpoint-specific activation and context-history repair.
    - [x] Add the strict default-off Web build/protocol gate with bounded query
      encoding, correlated descending-page validation, and shared application
      capability state; no UI or result cache is activated.
    - [ ] Add Web search result orchestration, accessible UI, context-history
      repair, and explicit endpoint activation evidence.
    - [ ] Add the equivalent Windows protocol, local state, accessible UI, and
      endpoint activation evidence.

Candidate slices:

- richer structured composition;
- group roles, invitations, join approval, mute, block, and moderation;
- Windows native notifications;
- end-to-end encryption only after a separate cryptographic design, device-key
  lifecycle, backup/recovery policy, and independent review;
- accessibility, localization, keyboard navigation, and low-bandwidth modes;
- voice/video through a dedicated real-time media architecture, not the chat
  message gateway.

Every capability must define protocol compatibility, local-cache behavior,
offline behavior, migration, abuse controls, observability, and rollback.

## Continuous Architecture Backlog

At every milestone:

- remove newly discovered responsibilities from oversized classes/stores;
- keep ADRs and architecture diagrams aligned with deployed reality;
- review dependency and platform support windows;
- update threat models;
- measure client startup, conversation-open, send-ack, sync, and memory behavior;
- keep at least one previous client version in compatibility tests;
- archive superseded migration code after its support window closes.
