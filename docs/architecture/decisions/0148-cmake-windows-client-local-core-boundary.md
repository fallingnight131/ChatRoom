# ADR-0148: CMake Windows Client Local-Core Boundary

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestones: M2, M4

## Context

The supported Windows client already has tested non-UI boundaries for message
projection, local SQLite, optimistic commands, synchronization, attachment
outbox recovery, and V1 history normalization. Their qmake tests each compiled
overlapping implementation files and `Common/Message.cpp`. Migrating the Widgets
executable before these foundations would couple CMake work to GUI, Multimedia,
Windows integration, deployment, and installer evidence.

## Decision

- Build the existing Common message/protocol implementation once as
  `chatroom_v1_common`, shared by the server core and client local core.
- Build the existing non-UI client sources as the static CMake target
  `chatroom_client_local_data`: `MessageModel`, `LocalConversationRepository`,
  `OutgoingMessageService`, `ConversationSyncService`,
  `AttachmentOutboxService`, and `V1HistoryPageAdapter`.
- Keep Qt Core/SQL and Common as public link requirements. Do not introduce GUI,
  Widgets, Network, Multimedia, or platform APIs at this boundary.
- Compile the six existing test sources unchanged as focused CTest executables.
  The unified CMake gate must execute every test named `v1_*`, not merely build
  it.
- Keep qmake tests and the Windows product build until native CMake client,
  runtime deployment, installer, and clean-host equivalence are proven.

## Consequences

The most important offline/reconnect correctness code now has a reusable,
platform-neutral CMake link boundary and eight total CTests together with server
persistence coverage. This reduces duplication and exposes accidental UI or
platform coupling early. It does not change SQLite format, cache ownership,
protocol behavior, supported platforms, or Windows packaging.

## Migration and Rollback

Future transport and update-core libraries may consume Common or stand beside
this target; UI code must not be pulled into it for convenience. Reverting the
CMake target and tests leaves qmake behavior and all durable client data intact.

## Verification

- build Common and local-data static libraries in Release mode on macOS;
- execute message model, repository, outgoing message, sync, attachment outbox,
  and history adapter tests through CTest;
- execute schema and password tests in the same `v1_*` gate;
- retain the linked HeadlessServer process-health regression and Ubuntu CI.
