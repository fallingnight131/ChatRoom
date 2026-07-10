# Evolution Roadmap

## How to Use This Roadmap

Execute milestones in order, but deliver each as small vertical slices. A
milestone is complete only when its exit criteria are met. Revisit sequencing
through an ADR when evidence changes.

Do not begin a later distributed-systems milestone merely because its technology
is attractive. Reliability and client responsiveness come before service count.

## M0 — Architecture and Delivery Baseline

Goal: make the current system measurable and safe to change.

Work:

- adopt `AGENTS.md`, project skills, target architecture, and ADR process;
- reconcile `README.md`, `DESIGN.md`, protocol names, and actual implementation;
- document the current V1 JSON protocol and database schema from code;
- establish reproducible Qt server, Qt client, and web builds;
- add a small integration harness for login, room chat, direct chat, reconnect,
  recall, and file metadata;
- record baseline CPU, memory, connection count, message latency, SQLite latency,
  and package sizes;
- establish Windows release build automation.

Exit criteria:

- a clean checkout can be built using documented steps;
- critical V1 flows have automated smoke coverage;
- architecture-changing work requires an ADR;
- performance claims refer to a stored test scenario and result.

## M1 — Secure and Reliable V1

Goal: correct the highest-risk behavior before migration.

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

- retries do not create duplicate messages;
- reconnect recovers a documented range of missing messages;
- normal file bytes bypass the chat server;
- authentication and file threat models have regression tests.

## M2 — Client Data and Experience Foundation

Goal: make clients fast and offline-tolerant before major UI expansion.

Work:

- extract Qt transport, application services, sync engine, and local repository
  from the large window/controller;
- add desktop SQLite for messages, conversations, drafts, cursors, and pending
  sends;
- split the web chat store and add IndexedDB-backed repositories;
- render cached conversations immediately and synchronize in the background;
- add optimistic send states, retry, failure, delivered, and read presentation;
- add virtualized history and bounded media caches;
- define shared semantic design tokens and accessibility expectations.

Exit criteria:

- opening a recent conversation does not require a network round trip;
- clients reconcile optimistic and server messages without duplicates;
- restarting offline preserves drafts and pending messages;
- long histories do not grow view memory linearly.

## M3 — Java V2 Modular Backend

Goal: introduce the target Java platform without a big-bang cutover.

Work:

- create a Gradle multi-module Java workspace;
- implement the V2 envelope and generated Java/C++/TypeScript schemas;
- build the Netty gateway and modular application core;
- introduce PostgreSQL schema and repeatable migrations;
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

## M4 — Cross-platform Desktop Distribution

Goal: deliver trustworthy native installation and updates.

Work:

- move the Qt build from qmake toward CMake when touching build architecture;
- create Windows `windeployqt` packaging and a signed installer;
- add an optional MSIX/Store channel after the direct installer is stable;
- create native macOS builds with `macdeployqt`, Developer ID signing,
  hardened runtime, notarization, stapling, and DMG distribution;
- add Linux AppImage, followed by deb/rpm only when supported operationally;
- implement a signed update manifest, stable/beta channels, staged rollout, and
  rollback;
- test clean install, upgrade, protocol compatibility, and uninstall on native
  CI runners.

Exit criteria:

- Windows and macOS users install without warnings caused by missing project
  signing or notarization;
- upgrades preserve local message data and settings;
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
- desktop native notifications and future mobile push;
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

