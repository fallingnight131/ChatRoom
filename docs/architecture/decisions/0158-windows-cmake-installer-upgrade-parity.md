# ADR-0158: Windows CMake Installer Upgrade Parity

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0157 proves clean CMake-payload installation, startup, helper unsigned
rejection, uninstall, and account-data preservation. The canonical qmake gate
also exercises directory replacement during upgrade, client liveness locking,
downgrade refusal, and traceable registration. Switching packaging without those
checks would leave meaningful NSIS behavior dependent on the old input path.

## Decision

- Compile a synthetic `0.9.0` predecessor installer from the same CMake payload
  and install it before the canonical temporary CMake installer.
- Require the predecessor version in HKCU uninstall registration.
- Add a stale program sentinel, upgrade to canonical `VERSION`, and require
  whole-program replacement with no stage or backup directories left behind.
- Require account-local data to survive the upgrade and validate canonical
  display version, source revision, and stable install identity in registration.
- While the installed CMake client is alive, rerun the canonical installer and
  require exit code 4 with the process, program files, and account data unchanged.
- Run the predecessor again and require downgrade rejection with current
  registration, client bytes, and account data unchanged.
- Continue with helper unsigned rejection and final uninstall/data-preservation
  checks from ADR-0157.
- Treat the predecessor explicitly as an installer-mechanics fixture because it
  contains current executable and schema bytes.

## Consequences

The CMake payload now traverses the same synthetic install/upgrade/lock/
downgrade/uninstall mechanics as the qmake payload before the job builds its
canonical artifact. This removes the final known installer-input behavior gap
needed for a reversible packaging switch.

It still does not demonstrate compatibility with a genuinely released previous
client/database, protected signatures, Windows 10/11 clean hosts, or public
update endpoints.

## Migration and Rollback

The next commit may change the canonical NSIS payload variable from qmake to the
already-deployed and parity-checked CMake directory, retain qmake compilation as
a short-lived fallback, and bind the build-system identity in artifact metadata.
Rollback changes that single input back to qmake; both full gates remain
available during the migration window.

## Verification

- require static policy markers for predecessor registration, stale cleanup,
  traceability, running-client refusal, and downgrade refusal;
- parse the native workflow and run prior payload/artifact policy regressions;
- require the complete native CMake installer script to pass before canonical
  qmake NSIS assembly begins;
- do not interpret the synthetic predecessor as historical compatibility proof.
