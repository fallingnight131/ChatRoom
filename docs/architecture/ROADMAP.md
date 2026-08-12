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
  the client, update helper, and final Setup, and prove unsigned artifacts fail
  without producing evidence in native CI.
- [x] Independently revalidate Windows signature evidence against the exact
  final client/helper/Setup bytes, closed schema, release identity, and freshness
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
  exact parity-candidate hashes into artifact-manifest schema 3.
- [x] Add fail-closed CMake stable/beta update configuration injection with
  disabled-residue, HTTPS literal, one/two-public-key validation and execute
  default-off/enabled runtime configuration suites through CTest.
- [x] Add an independent protected-signing intake verifier for the complete
  schema-3 unsigned CMake artifact, closed identity/inventory/checksums, exact
  final bytes, parity evidence, required runtimes, and zero extra files.
- [x] Pin a Chromium/Firefox Playwright engine matrix and exercise login startup,
  browser capabilities, endpoint isolation, and narrow responsive layout.
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

Candidate slices:

- multi-device login and device management;
- reply, quote, forward, edit policy, reactions, mentions, and pinned messages;
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
