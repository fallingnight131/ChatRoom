# ADR-0156: Windows CMake Deployed Payload Parity

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0155 added native CMake executable compilation while retaining qmake as the
installer source. Successful compilation does not prove that `windeployqt`
discovers the same Qt modules/plugins, that SQLite and libsodium are present, or
that the resulting directory excludes development/server files.

Switching NSIS input before deployment parity would make a missing DLL appear as
an installer or startup failure and would weaken the qmake rollback comparison.

## Decision

- Deploy CMake `ChatClient` and `ChatRoomUpdateLauncher` into a separate native
  Windows CI directory using the same pinned `windeployqt` and libsodium source.
- Compare that directory against the exercised qmake payload with a provider-
  neutral tool.
- Require the exact same case-sensitive relative inventory and reject
  case-insensitive collisions, symlinks, empty/non-regular files, debug/linker
  artifacts, and a bundled server.
- Require exactly one root libsodium DLL and the Qt SQLite plugin.
- Permit byte differences only for `ChatClient.exe` and
  `ChatRoomUpdateLauncher.exe`; require identical size and SHA-256 for every
  deployed runtime DLL, plugin, and data file.
- Emit a closed schema containing both full hashed inventories only after every
  comparison succeeds. Never create evidence on rejection.
- Validate the evidence identity, closure, runtime equality, version, and source
  revision again and bind its hash into the uploaded unsigned artifact manifest.
- Continue using the qmake payload as NSIS input in this slice.

## Consequences

A CMake build can no longer advance merely because its two executables link.
Deployment dependency drift becomes an attributable failure before installer
mutation. Uploaded verification artifacts carry tamper-evident proof of the
comparison even though the duplicate CMake directory is not uploaded.

This gate does not compare executable behavior, signatures, helper handshakes,
installation, or upgrade. Those remain the next native parity slice.

## Migration and Rollback

Next, compile a separate NSIS verification installer from the CMake payload and
exercise install/launch lock/helper unsigned rejection/upgrade/uninstall without
replacing the qmake artifact. Only a later atomic switch may make CMake the
canonical packaging input. Rollback removes the comparison step and leaves the
existing qmake installer unchanged.

## Verification

- run portable success and executable-difference acceptance fixtures;
- reject missing/extra/runtime-mutated/case-colliding/symlink payloads without
  evidence;
- reject mutated or open-schema parity evidence during artifact-manifest binding;
- parse the changed workflow and run the existing 27 CTest/health regression;
- require native Windows deployment parity before the job may reach NSIS.
