# Chat Room Engineering Guide

## Mission

Evolve this repository from a Qt/C++ single-node chat room into a reliable,
secure, cross-platform instant-messaging product through small, reversible
iterations. Preserve a working product at the end of every iteration.

The long-term direction is documented in
[`docs/architecture/README.md`](docs/architecture/README.md). The ordered
migration plan is documented in
[`docs/architecture/ROADMAP.md`](docs/architecture/ROADMAP.md).

## Current System

- `Server/`: Qt/C++ TCP, WebSocket, HTTP, SQLite, local/COS file integration.
- `Client/`: Qt Widgets desktop client, currently oriented toward Windows.
- `WebClient/`: Vue 3, JavaScript, Pinia, Vite.
- `Common/`: the current V1 JSON protocol shared by the Qt server and client.

Inspect the implementation before making architectural claims. The maintained
architecture, protocol, data, and delivery documentation lives under `docs/`.

## Project Skills

Use the relevant repository skill before acting:

- `.agents/skills/guide-chat-architecture`: cross-module design, Java backend,
  storage, deployment topology, migrations, and architecture decisions.
- `.agents/skills/evolve-im-protocol`: message schemas, delivery guarantees,
  ordering, idempotency, synchronization, database message models, and protocol
  compatibility.
- `.agents/skills/ship-cross-platform-clients`: Qt/QML, Vue, local client data,
  Windows/macOS/Linux integration, installers, signing, and updates.
- `.agents/skills/verify-chat-quality`: tests, security, performance,
  observability, failure modes, and release gates.

Use all applicable skills for a change that crosses those concerns.

## Architectural Rules

1. Prefer vertical, releasable slices over a big-bang rewrite.
2. Keep V1 clients working until a documented compatibility window ends.
3. Version every externally visible protocol change. Never silently reinterpret
   an existing field or message type.
4. Treat the server as the authority for identity, membership, permissions,
   message timestamps, sequence numbers, and file access.
5. Make message submission idempotent. Use a client-generated message ID and a
   server-assigned stable message ID and conversation sequence.
6. Use sequence-based incremental synchronization. Do not rely on timestamps or
   page offsets as the only ordering mechanism.
7. Keep file bytes out of the target message path. Clients should upload to
   object storage with short-lived authorization; chat messages carry metadata.
8. Store durable truth in the primary SQL database. Treat Redis, in-process
   maps, search indexes, and queues as rebuildable or explicitly replicated
   infrastructure.
9. Add a message broker only when multi-instance routing or asynchronous load
   requires it. Do not introduce infrastructure without an observed need and an
   operations plan.
10. Keep platform UI idiomatic. Share protocol and product behavior across
    clients, not platform-specific window behavior.
11. Never commit secrets, signing keys, `.env` files, access tokens, or production
    endpoints.
12. Do not claim performance or capacity without a reproducible benchmark.

## Change Workflow

Before implementation:

1. Read the relevant source paths and the applicable project skills.
2. Identify the affected quality attributes: correctness, compatibility,
   security, user experience, performance, operability, or scalability.
3. Define the smallest end-to-end slice and its rollback path.
4. Record an ADR under `docs/architecture/decisions/` when changing a service
   boundary, persistent data model, protocol guarantee, primary technology,
   packaging channel, or security model.

During implementation:

1. Preserve unrelated user changes and avoid broad mechanical rewrites.
2. Keep UI, application logic, transport, and persistence boundaries explicit.
3. Add compatibility adapters at system boundaries instead of spreading legacy
   conditions through the domain model.
4. Use expand-migrate-contract for schema and protocol migrations.
5. Add diagnostics at new network, storage, and asynchronous boundaries.

Before completion:

1. Run the smallest relevant tests, then the broader build for touched targets.
2. Test negative and reconnect paths for authentication, protocol, and storage
   changes.
3. Update architecture, protocol, deployment, and migration documentation in the
   same change when behavior or constraints change.
4. State what was verified and what could not be verified.

## Commit Discipline

Treat each completed, independently verifiable plan step as a commit boundary.

1. Finish the step's implementation, documentation, and relevant verification.
2. Review the diff and stage only files that belong to that step.
3. Commit immediately with a concise message that names the completed outcome.
4. Keep unrelated work and later roadmap steps out of the commit.
5. Do not commit a knowingly broken build, failing required test, secret, local
   artifact, or generated output that should be ignored.
6. Do not amend, squash, rebase, or rewrite an existing commit unless the user
   explicitly requests it.

If a coherent step cannot be committed because the worktree contains overlapping
user changes, preserve those changes and report the blocker instead of mixing
ownership.

## Component Boundaries

### Current C++ server

- Keep transport parsing in `ClientSession` and business decisions outside it.
- Do not add new unrelated responsibilities to `ChatServer`; extract a focused
  application service when modifying a large handler area.
- Keep SQL inside `DatabaseManager` during V1 maintenance, but do not expose SQL
  row shapes directly to clients.
- Do not add more connection-per-thread behavior. New connection work should
  move toward bounded event-loop or worker-pool ownership.

### Target Java backend

- Build a modular monolith plus an independently scalable IM gateway first.
- Use domain/application interfaces between modules; do not create network calls
  merely to imitate microservices.
- Default target modules are gateway, identity, conversation, messaging,
  contacts, groups, attachments, notification, and administration.
- Introduce a separate deployable service only when scaling, isolation,
  ownership, or availability requirements justify it.

### Desktop client

- Preserve the Qt client and migrate incrementally.
- Extract transport, synchronization, local storage, and view models before
  replacing large Widgets screens with QML.
- Keep OS integration behind platform adapters.

### Web client

- Move new code toward TypeScript and feature-oriented modules.
- Keep durable message/cache state in IndexedDB; keep Pinia focused on live view
  state and orchestration.
- Do not accumulate additional unrelated behavior in the existing large chat
  store.

## Verification Matrix

Run what applies to the touched scope:

- M0 repository baseline: use `python3 tools/verify_m0.py` and add the relevant
  `--web`, `--db-schema`, `--v1-smoke`, `--performance`, or `--qt` flags. Use
  `--all` only when the host has every documented dependency.
- Web: `npm ci`, `npm test`, then `npm run build` from `WebClient/`.
- Current Qt server/client: generate a release build with the available Qt
  toolchain and compile the touched target.
- Future Java modules: run the Gradle unit and integration test tasks once the
  Java workspace exists.
- Protocol: test old-client/new-server and new-client/compatible-server paths.
- Database: test migration forward, restart after migration, and rollback or
  documented restore procedure.
- Packaging: install, launch, upgrade, uninstall, and verify signatures on each
  target operating system.

Do not treat an unsigned M0 CI artifact as an installer. Windows installer
signing and macOS signing/notarization are M4 release gates.

## Definition of Done

A change is complete only when:

- the intended user path works;
- compatibility and failure behavior are defined;
- security checks remain server-side;
- relevant builds/tests pass;
- operational signals exist for new critical paths;
- documentation reflects the new truth;
- no unsupported scale or reliability claim is made.
