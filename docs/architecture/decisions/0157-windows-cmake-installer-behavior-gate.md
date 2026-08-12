# ADR-0157: Windows CMake Installer Behavior Gate

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0156 proves that CMake and qmake deploy the same runtime inventory and bytes,
except for their two build-system-specific executables. It does not prove those
executables start, that the generated PE version resources are visible after
installation, or that the CMake helper implements the native ready/commit and
unsigned-rejection contract.

Changing the canonical NSIS input before exercising those differences would
move compilation, deployment, process, and installer risk at once.

## Decision

- After deployed-payload parity, compile a second explicitly unsigned temporary
  NSIS whose input is the CMake payload.
- Install into an isolated runner directory and require the client, helper, Qt
  SQLite driver, and exactly deployed libsodium runtime to be present.
- Require installed client/helper PE product versions to start with canonical
  `VERSION`.
- Start the installed client and require it to remain alive before terminating
  it for the test.
- Drive the installed helper through named ready and commit events bound to a
  live parent process and a UUID request.
- Supply a copied unsigned Setup and require exit code 4, closed
  `trust-rejected` result evidence, exact request identity, and fixture deletion.
- Silently uninstall and require removal of program files while a test sentinel
  in account-local data survives.
- Execute cleanup in a `finally` path so failed native checks do not contaminate
  the later qmake installer gate.
- Keep this CMake installer temporary and unuploaded. Continue using the qmake
  payload for the canonical unsigned artifact.

## Consequences

Native Windows CI now checks the executable differences that payload hashing is
intentionally unable to compare. A CMake resource, startup, helper protocol, or
NSIS compatibility regression blocks the job before the existing release
artifact is assembled.

This slice duplicates a focused clean install. It does not yet prove previous-
version upgrade, downgrade refusal, running-client mutation refusal, or the full
artifact manifest against a CMake installer.

## Migration and Rollback

Next, exercise the full synthetic predecessor upgrade/downgrade and running-
client gates using CMake payloads. Once those results match, switch the canonical
NSIS input in one reversible commit while retaining qmake compilation briefly as
a fallback. Rollback removes the focused script invocation without changing the
current uploaded artifact.

## Verification

- run the cross-host gate policy and workflow YAML parse;
- run payload comparator and artifact-manifest regression tests;
- require native Windows clean install/start/helper rejection/uninstall success;
- retain qmake's existing full installer gate after the CMake check;
- do not claim signing, upgrade, or clean-host support from this focused gate.
