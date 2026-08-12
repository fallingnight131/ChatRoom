# ADR-0149: CMake Windows Client Transport Boundary

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestones: M1, M4

## Context

The supported Windows client has three non-UI transport implementations:
length-prefixed V1 TCP lifecycle in `NetworkManager`, tokenized raw HTTP upload,
and streaming HTTP download. qmake tests compiled overlapping source sets, while
the new CMake client-local boundary stopped below network I/O. Migrating the
Widgets executable before transport would make GUI and packaging responsible
for proving reconnect and file-data-plane equivalence.

## Decision

- Build the existing `NetworkManager`, `HttpUploadTransport`, and
  `HttpDownloadTransport` sources as `chatroom_client_transport`.
- Keep this static library limited to Qt Core/Network and
  `chatroom_v1_common`. It must not depend on Widgets, local SQLite, updater,
  shell integration, or unsupported native platforms.
- Compile the existing upload, download, and reconnect test sources unchanged as
  CTest executables.
- Execute, rather than merely build, all tests through the common `v1_*` CTest
  gate. Give network tests a fixed 30-second timeout.
- Keep qmake and the native Windows product path during CMake equivalence.
- Treat the discovered `QSslSocket::VerifyNone` optional TLS behavior as a
  separate critical security correction with its own negative certificate tests;
  do not hide that behavior change inside this build-only migration.

## Consequences

Transport, local data, and server persistence now have explicit reusable CMake
boundaries below the Windows UI. The tests exercise exact upload bytes and
tokenized path, streamed temporary download plus denial cleanup, and reconnect
reauthentication from memory with failed-restore session clearing. This build
boundary does not yet make V1 public transport safe: certificate verification
and a TLS-capable server/proxy deployment remain required.

## Migration and Rollback

The future Windows CMake executable can link transport rather than re-list its
sources. Reverting this target/test registration leaves qmake output and network
behavior unchanged. Do not remove qmake transport tests until native Windows
CMake product equivalence is recorded.

## Verification

- Release-build the transport library and three existing tests on macOS;
- upload and download loopback tests pass with exact request/response behavior;
- reconnect test performs three connections, restores credentials only from
  memory, delays the recovered signal until authentication, and clears state on
  rejection;
- every network CTest has a 30-second timeout and Ubuntu CI runs the same gate;
- the linked HeadlessServer process regression continues to pass.
