# ADR-0141: Immutable Signed Windows Release Candidate

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4
- Extends: ADR-0139, ADR-0140

## Context

Signature evidence covers the project-owned client, update helper, and Setup,
but Windows users receive a complete `windeployqt` directory. Publishing those
three trusted files beside a different or incomplete runtime could omit the
platform or SQLite plugin, omit libsodium, include the server/debug symbols, or
make the uploaded bytes differ from the independently checked paths.

## Decision

- Add a provider-neutral `assemble`/`verify` tool for a complete signed Windows
  release candidate. It accepts the final client payload, canonical Setup,
  ADR-0139 evidence, version/Git/channel/Qt identity, and reviewed publisher
  SHA-256; it accepts no signing secret.
- Before copying, require fresh ADR-0140 evidence for the exact source client,
  helper, and Setup. Require `ChatClient.exe`, `ChatRoomUpdateLauncher.exe`,
  `Qt6Core.dll`, `platforms/qwindows.dll`, `sqldrivers/qsqlite.dll`, and one
  top-level libsodium DLL.
- Reject server executables, `.env*`, private-key/certificate containers,
  object/link/debug outputs, symlinks, an existing destination, or any payload
  file outside `client/`.
- Copy into a sibling temporary directory, copy evidence and Setup into fixed
  paths, and repeat ADR-0140 against the copied bytes before generating
  metadata. Hash every candidate file into a sorted closed manifest and
  `SHA256SUMS`; validate the complete temporary tree, then rename it atomically
  to a previously absent destination.
- Mark the result `signed-timestamped-not-published-candidate`. Assembly is not
  publication, support, or positive signing evidence; fixture tests construct
  evidence only to exercise policy.
- A future protected workflow may upload only this candidate directory after a
  separate clean-install/upgrade/uninstall and rollback gate. Update-manifest
  signing must use the Setup bytes inside the verified candidate, never the
  pre-assembly source path.

## Consequences

The release unit is now the whole runtime and not a loose trio of signed files.
It can be copied to a clean Windows host and independently verified without
access to signing credentials. Third-party Qt/runtime publisher identities are
not rewritten as the project's publisher identity; their bytes are instead
closed by the candidate manifest and later clean-host execution.

## Migration and Rollback

No production channel changes. Candidate destinations are immutable: correcting
one requires a new empty destination and regenerated evidence, never in-place
mutation. Rollback removes the assembler until a protected workflow depends on
it; afterward it requires a replacement full-payload integrity gate and ADR.

## Verification

- a complete fixture assembles atomically and passes independent verification;
- missing Qt Core/platform/SQLite/libsodium, server/debug/key/environment files,
  existing destinations, symlinks, tampered bytes, undeclared files, and wrong
  channel identity fail;
- baseline CI runs candidate policy tests;
- real Authenticode, clean-host execution, and public channel publication remain
  external M4 gates and are not claimed by fixtures.
