# ADR-0116: Transactional Windows Program-Directory Upgrade

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The initial NSIS installer copied a new payload directly over the installed
directory. Files removed by a later version could remain, and a partial copy
could mix releases. Account data already lives separately under roaming AppData,
so the replaceable per-user program directory can use a stronger whole-directory
upgrade boundary without migrating conversations or settings.

## Decision

- Give every installer-owned program directory a fixed product ID in
  `.chat-room-install.ini`, together with version and source revision. Both
  upgrade and uninstall require the marker plus `ChatClient.exe` before recursive
  removal. Refuse an occupied unmarked target or pre-existing staging/backup
  content.
- Extract the complete payload, canonical marker, and uninstaller into a sibling
  `.__chatroom_stage` directory. Validate the client executable and Qt SQLite
  driver before changing the active program directory.
- For upgrade, rename the active directory to `.__chatroom_backup`, rename the
  validated stage into place, and restore the backup when activation fails. For
  a fresh install, atomically rename the validated stage into the empty target.
- Remove the old backup only after the new directory becomes active. Treat a
  cleanup failure as an installer error requiring operator attention; never
  silently report a clean upgrade while stale program bytes remain.
- Compare the incoming numeric SemVer with the owned marker. Permit repair of
  the same version and forward upgrades; reject a direct older Setup before any
  staging or active-directory mutation.
- Clean only the staging directory created by the current installer on failure.
  Do not delete arbitrary similarly named content or account data.
- Extend native Windows CI with a synthetic `0.9.0` installer built from the
  same verification payload, install it, add a stale program sentinel and an
  AppData sentinel, then install the canonical current version. Require stale
  program removal, AppData preservation, canonical executable/registry state,
  no stage/backup residue, and successful uninstall with AppData preserved.
  Re-run the synthetic older Setup after upgrade and require nonzero exit while
  the current registration, executable, and AppData sentinel remain unchanged.
- Keep the synthetic predecessor outside the uploaded artifact. It tests NSIS
  state transitions, not old-binary/database compatibility and is never a
  release candidate.

## Consequences

The direct installer now replaces one coherent program tree instead of merging
files. Upgrade failure before activation leaves the old release untouched;
failure during activation attempts an immediate directory rollback.

This does not handle a running/locked client, power loss during the two renames,
real older executable/schema compatibility, signed-channel rollback policy,
Authenticode, timestamping, updater orchestration, or Windows 10/11 clean-host
behavior. Those remain M4 gates. Hosted Windows Server CI is configured for the
synthetic path, but the workflow result is not claimed until it runs remotely.

## Migration and Rollback

No public installer has shipped, so there is no markerless installed population
to migrate. The first supported build must use this marker. Rollback before
public release removes the staging/swap logic and returns to the explicitly
unsigned verification installer; do not deploy a marker-requiring uninstaller
over an unknown historical installation.

## Verification

- policy tests require marker ownership, staged payload validation, forward
  directory swap, reverse rename rollback, current-stage cleanup, and the native
  synthetic-upgrade assertions;
- NSIS 3.12 compiles the revised script locally on the macOS development host
  with warnings-as-errors and a DLL-shaped fixture payload;
- native CI owns actual Windows install/upgrade/uninstall evidence.
