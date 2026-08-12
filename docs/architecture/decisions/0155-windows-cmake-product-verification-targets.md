# ADR-0155: Windows CMake Product Verification Targets

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADRs 0148 through 0154 migrated the non-UI Windows client, transport, updater,
lifecycle, and handoff layers into reusable CMake libraries. Those libraries
were initially nested under `BUILD_TESTING`, so a test-disabled product build
could not consume them. The remaining Qt Widgets shell and update helper still
had only qmake executable graphs.

A direct packaging switch would combine build-system migration with deployed
payload and installer changes, weakening rollback and making failures difficult
to attribute.

## Decision

- Make reusable client/updater libraries available when either tests or the
  Windows product target are requested.
- Add `CHATROOM_BUILD_WINDOWS_CLIENT`, default off and rejected on non-Windows
  hosts.
- Build Windows-subsystem `ChatClient` and `ChatRoomUpdateLauncher` executables
  from the reusable libraries plus their remaining UI/process entry sources.
- Read the canonical root `VERSION` during configuration and generate explicit
  PE version/icon resources for both executables.
- Keep product update trust disabled; this target introduces no manifest URL or
  public product key.
- Add a source-graph policy that requires every source in both qmake product
  projects to remain represented in CMake.
- Build both CMake executables with pinned Qt/MSVC in native Windows CI and
  verify their PE product versions.
- Continue assembling the qmake outputs into the unsigned payload and NSIS
  installer until the deployed CMake payload has equivalent evidence.

## Consequences

The repository now has an explicit CMake product graph without claiming that a
Mac configuration is Windows release evidence. A non-Windows host fails fast if
the product option is enabled. Native CI can reveal compiler, resource, system-
library, and source-graph issues while the existing qmake packaging path remains
releasable and attributable.

For one migration window CI compiles both build systems, increasing build time.
The qmake path may be removed only in a later commit after CMake deployment and
installer parity is demonstrated.

## Migration and Rollback

Next, assemble a separate `windeployqt` payload from the CMake executables and
compare its required runtime inventory and native negative update-helper paths.
Only then switch the NSIS input. Rollback is disabling the CMake option/job;
qmake packaging and all runtime formats are unchanged by this decision.

## Verification

- run the cross-host source/policy check;
- configure and build the test-disabled CMake server path on macOS to ensure the
  new option does not pull client libraries into unrelated builds;
- require non-Windows configuration with the product option to fail;
- build both executables and check canonical PE product versions in native
  Windows CI;
- do not count CI compilation alone as installation, signing, or support proof.
