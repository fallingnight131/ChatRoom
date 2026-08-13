# ADR-0323: Static Windows V2 Protobuf Runtime

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The reviewed Windows V2 C++ bindings and detached WSS/session adapter compile in
an isolated protocol test, but the supported Windows product does not yet
compile them. Adding a dynamically linked Protobuf package to vcpkg would add
Protobuf and possibly Abseil runtime DLLs to both the canonical CMake payload
and the qmake rollback payload. That increases installer inventory and creates
a second dependency path solely to keep a legacy build graph feature-equivalent.

## Decision

- Compile the reviewed V2 bindings, protocol clients, and Qt WSS adapter into a
  dedicated `chatroom_windows_v2_transport` static library in the canonical
  Windows CMake product graph.
- Fetch Protobuf 35.1 and Abseil 20250512.1 from immutable release archives with
  exact SHA-256 checksums and build them statically. The product uses the same
  dependency contract as the three-language protocol gate.
- Keep `protoc`, tests, examples, installation rules, and shared-library output
  disabled. Generated sources remain committed review artifacts; a Windows
  product build does not run a code generator.
- Do not add Protobuf to the qmake fallback or the vcpkg runtime payload. qmake
  remains rollback/parity evidence for the V1 surface; CMake is the canonical
  Windows packaging input under ADR-0159.
- Link the detached V2 library into `ChatClient` now so native MSVC compilation
  is a release gate. Do not expose or start it until the application/UI
  composition slice supplies explicit V2 endpoint and preview/cutover policy.

## Consequences

The Windows installer gains no Protobuf/Abseil DLLs, and its closed runtime
inventory remains comparable with the qmake fallback. Windows builds require
network access to the two checksum-pinned source archives when they are absent
from the CMake FetchContent cache, so the native job timeout is raised from 30
to 45 minutes. Rollback removes the V2 target/link without changing installed
data, V1 protocol behavior, or the package inventory.

## Verification

- require the exact dependency versions, hashes, static linkage policy, and V2
  target from the Windows CMake source-graph test;
- compile and run the isolated generated-binding, session, device, and Qt WSS
  tests against the same dependency helper;
- compile `ChatClient` with MSVC in the native Windows product lane;
- require deployed qmake/CMake runtime inventory parity and no new protocol DLL.
