# ADR-0108: Versioned Windows Verification Payload

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

Native Windows CI builds the Qt client and server and uses `windeployqt`, but
the uploaded directory previously mixed client and server binaries, repeated a
hard-coded application version, and had only an ad hoc checksum list. That is
useful build evidence but is a weak input to the later installer, signing, and
update pipeline.

An unsigned directory must also remain visibly distinct from a supported
installer. Adding a checksum file does not provide publisher authenticity,
timestamping, installation behavior, or rollback.

## Decision

- Add a root `VERSION` file containing one canonical numeric SemVer line
  (`major.minor.patch`), compatible with Windows/Qt file-version metadata. The
  Windows Qt application version and native verification artifact both consume
  it; future beta/stable distinction belongs in the signed channel metadata.
- Keep building both current Qt targets as a compatibility gate, but upload only
  the Windows client payload. The legacy server executable is not part of the
  desktop client distribution boundary.
- Generate a deterministic `artifact-manifest.json` and `SHA256SUMS` after
  `windeployqt`. The manifest identifies schema, product, version, Windows
  x86_64/MSVC 2022/Qt version, exact Git revision, every client file's size and
  SHA-256, and the fixed `verification` channel.
- Label the manifest `unsigned-verification-only`. The generator cannot emit a
  stable/beta release or claim a signature.
- Reject noncanonical version/revision/toolchain input, empty payloads,
  symlinks, non-regular files, and files that change while hashing. Sort paths
  and omit wall-clock timestamps so the metadata is reproducible for identical
  inputs.
- Keep signing credentials and signing logic out of this slice. A later ADR and
  native acceptance gate will define installer identity, certificate custody,
  timestamp service, update-manifest signature, and rollback policy.

## Consequences

Every Windows verification payload is now traceable to source and has a
machine-readable integrity inventory suitable as an input to installer work.
The client/server product boundary is explicit.

ADR-0110 subsequently advances the manifest to schema 2 and records the exact
unsigned NSIS verification installer separately from its client payload.

The payload is still not an installer or a supported release. SHA-256 detects
change but does not authenticate the publisher. Windows 10/11 clean-host
install, launch, upgrade, local-data preservation, uninstall, Authenticode, and
automatic-update evidence remain M4 gates.

The root version governs only the Windows desktop product. ADR-0109 subsequently
keeps the Web package/lock version independent so the supported clients can ship
on different cadences.

## Migration and Rollback

The change does not alter user data, protocol, or runtime services. Rollback
restores the previous CI assembly and hard-coded application version. Already
uploaded verification artifacts remain explicitly unsigned historical evidence.

## Verification

- Python tests prove deterministic ordering/output and rejection of invalid
  SemVer, Git revision, Qt version, empty payloads, and symlinks where supported;
- the Qt source/build gate proves `Client.pro` injects the root version into the
  application;
- native Windows CI builds both Qt targets, assembles only the client runtime,
  requires the SQLite driver, generates the manifest, and uploads an artifact
  whose name includes version, unsigned status, and Git revision;
- documentation continues to state that only a signed, timestamped and tested
  installer can satisfy the M4 release gate.
