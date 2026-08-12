# ADR-0159: Promote Windows CMake Packaging Input

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADRs 0155-0158 establish source parity, native compilation, deployed runtime
byte parity, clean install/helper behavior, synthetic upgrade, running-client
locking, downgrade refusal, and uninstall behavior for the CMake Windows
payload. Continuing to package qmake after those gates would leave two product
graphs indefinitely and make future CMake-only work appear verified without
actually shipping it.

The switch must remain attributable and reversible. The final artifact must not
silently receive bytes different from the CMake candidate that passed parity.

## Decision

- Continue compiling and deploying qmake into
  `build/m4/windows-qmake-payload` as a temporary fallback and parity baseline.
- Continue building, deploying, comparing, and fully exercising the CMake
  payload before promotion.
- After the complete CMake installer gate succeeds, copy
  `build/m4/windows-cmake-payload` to the canonical
  `build/m0/artifacts/windows/client` directory.
- Recompare the canonical copy byte-for-byte with its CMake source before NSIS
  compilation, explicitly including both executables.
- Compile the existing full synthetic predecessor/current NSIS gate from the
  canonical CMake directory and upload that unsigned verification artifact.
- Advance the Windows artifact manifest to schema 3 with a closed
  `buildSystem` value and require `cmake` in the canonical workflow.
- Bind the earlier qmake/CMake parity evidence by hash and independently require
  its CMake candidate inventory to equal the final canonical payload inventory.
- Retain product updates disabled and make no signing/publication change.

## Consequences

CMake is now the canonical Windows client/helper build and NSIS payload source.
qmake remains extra CI cost only as a short-lived comparison and rollback path.
The uploaded manifest makes the build-system identity explicit and prevents a
post-comparison directory substitution from passing unnoticed.

This is still an explicitly unsigned Windows Server 2025 verification artifact,
not a publisher-signed Windows 10/11 support claim or public release.

## Migration and Rollback

Keep the qmake fallback until multiple native CI runs are stable and CMake gains
the protected update-configuration path. A rollback changes the canonical copy
source to the qmake baseline and records `buildSystem: qmake`; no runtime,
database, installer, or protocol format changes are required.

The next build-system cleanup may remove qmake product compilation only after a
separate ADR verifies no release or update-configuration dependency remains.

## Verification

- require workflow ordering: qmake baseline, parity, full CMake installer gate,
  CMake promotion, canonical NSIS, artifact manifest;
- recompare the promotion copy and reject any byte/inventory change;
- require artifact schema 3, `buildSystem: cmake`, parity evidence integrity,
  and exact canonical-to-candidate inventory equality;
- run payload, installer, promotion, artifact, and workflow policy regressions;
- retain all existing canonical NSIS install/upgrade/lock/downgrade/uninstall
  checks after promotion.
