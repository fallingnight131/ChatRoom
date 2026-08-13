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

Work:

- move gateway routing and presence leases to Redis;
- run multiple gateways behind a load balancer;
- introduce a durable broker and transactional outbox for cross-node delivery
  and background consumers;
- define partition keys that preserve per-conversation order;
- isolate push, thumbnail, scanning, retention, audit, and analytics workers;
- implement graceful drain, rolling deployment, and reconnect-storm controls;
- add database partitioning/read strategies only after query evidence.

Exit criteria:

- losing one gateway does not lose committed messages;
- users connected to different gateways exchange messages correctly;
- duplicate event delivery remains safe;
- chaos/load tests verify the documented failure and capacity envelope.

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
    - [ ] Persist Web mention metadata with authoritative messages and pending
      submission/edit workflows, then add composition, rendering,
      accessibility, reconnect convergence, and Web capability activation.
    - [ ] Add the equivalent Windows protocol, SQLite, composition, rendering,
      accessibility, reconnect convergence, and capability activation gates.

Candidate slices:

- forwarding and richer structured composition;
- group roles, invitations, join approval, mute, block, and moderation;
- full-text search with an asynchronously rebuildable index;
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
