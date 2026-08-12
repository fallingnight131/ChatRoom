# ADR-0147: CMake V1 Persistence Library Boundary

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0146 proved a CMake representation of the V1 HeadlessServer, but the target
still compiled every production source directly. Migrating each test that way
would repeat the same persistence implementation and dependency declarations,
recreating the qmake source-list duplication inside CMake. Database schema
integrity is the first useful reusable boundary because it depends only on
`DatabaseManager`, `PasswordHasher`, Qt Core/SQL, and libsodium.

## Decision

- Build `DatabaseManager` and `PasswordHasher` once as the static CMake target
  `chatroom_v1_persistence`, with public Qt Core/SQL, libsodium, and Server
  include requirements.
- Build the remaining Common/Server implementation as
  `chatroom_v1_server_core`, link it to persistence, and keep the headless
  executable as a thin `main.cpp` target.
- Migrate the existing `DatabaseSchemaTest.cpp` source unchanged into a CMake
  executable linked only to the persistence target; register it with CTest.
- Make `verify_m0.py --cmake-headless` build both the server and schema test,
  execute the focused CTest entry with failure output, then start the real
  server health test.
- This is a build/link boundary, not a new runtime service, network API, data
  store, or ownership model. SQL remains inside `DatabaseManager` for V1.

## Consequences

New CMake tests can reuse persistence without compiling the whole server or
copying its source list. The server binary still contains the same production
implementation, and the unchanged schema test verifies clean creation, restart
equivalence, required columns, integrity, and query-plan indexes. Static-library
boundaries can expose accidental coupling at link time, which is useful, but do
not by themselves make the legacy class a clean domain repository.

## Migration and Rollback

Migrate additional focused tests only when they naturally consume an existing
library boundary. qmake test projects remain available during equivalence.
Reverting the library/test targets returns to the direct executable source list
without changing database bytes or application behavior.

## Verification

- configure and build persistence, server core, thin executable, and schema test
  in Release mode on macOS;
- CTest passes the unchanged clean/restart schema and query-plan suite;
- the linked HeadlessServer passes the real `/api/health` process suite;
- Ubuntu CI runs the same unified command.
