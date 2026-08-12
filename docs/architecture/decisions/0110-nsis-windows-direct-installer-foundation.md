# ADR-0110: NSIS Windows Direct Installer Foundation

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0108 creates a DLL-complete Windows client payload but explicitly does not
install it. The direct distribution channel needs deterministic installation,
Add/Remove Programs metadata, uninstall behavior, and preservation of account-
local SQLite/settings data before signing and automatic updates can be added.

The maintainer develops on macOS, so native Windows CI must own executable
installer acceptance. The current repository has no production signing
certificate or approved timestamp service, and those secrets must not be
simulated or committed.

## Decision

- Use pinned NSIS 3.12 to build the initial direct `Setup.exe`. NSIS provides a
  scriptable compiler, user-level installer/uninstaller, Unicode UI, CRC
  checking, Windows manifest/version metadata, and a later external signing
  boundary without requiring a runtime framework in the installed client.
- Keep the installer script parameterized by canonical Windows `VERSION`, exact
  Git revision, already verified `windeployqt` payload, output directory, and
  icon. Missing inputs fail compilation.
- Install per-user under `$LOCALAPPDATA\Programs\ChatRoom` without elevation.
  Register version, source revision, install location, normal uninstall, and
  quiet uninstall under the current user's standard uninstall registry key.
- Install only the Windows client payload. Do not bundle `ChatServer.exe` or
  mutate account-local data under `$APPDATA\QtChatRoom\ChatClient`.
- Refuse recursive uninstall when the expected client executable is absent,
  remove only the dedicated install/start-menu directories and uninstall key,
  and preserve account-local database/settings for reinstall and upgrade.
- Name this artifact `unsigned-verification-Setup.exe`. Native CI must confirm it
  has no Authenticode signature, silently install it into an isolated path,
  verify executable/version/SQLite driver/uninstall metadata, silently uninstall
  it, and prove an account-local sentinel survives.
- Extend the Windows artifact manifest to schema 2 and record the NSIS
  installer size/hash/status separately from the installed client payload.
- Defer all signing. The release pipeline must later sign relevant binaries,
  uninstaller, and outer Setup with protected key custody, SHA-256 digest, RFC
  3161 SHA-256 timestamp, and `signtool verify /pa` acceptance.

## Alternatives Considered

- Inno Setup 7: technically capable, but its current commercial licensing would
  add a licensing decision before the project has a release owner and budget.
- WiX/MSI: viable for enterprise deployment, but adds a larger authoring/tooling
  surface before the direct per-user channel is proven.
- MSIX first: rejected by ADR-0009 sequencing; the optional Store/MSIX channel
  follows a stable direct installer and has different identity/update rules.
- A zip-only payload: retained as CI evidence but insufficient for normal users,
  Add/Remove Programs, upgrade, and uninstall behavior.

## Consequences

Windows CI can now produce and exercise a real Setup/uninstall pair while the
project remains honest that it is unsigned and unsupported for public release.
The fixed per-user boundary avoids an unnecessary UAC prompt and keeps user data
outside the replaceable program directory.

This does not prove Windows 10/11 clean-host launch, cross-version upgrade,
locked-file handling, accessibility, SmartScreen reputation, Authenticode,
timestamp durability, staged rollout, or automatic rollback. The hosted
Windows Server runner is build/install evidence, not the final support matrix.

## Migration and Rollback

No protocol or data schema changes. Users do not receive this CI artifact. The
installer can be removed by its uninstaller, while account-local data remains.
Rollback removes the NSIS stage and returns to the client verification payload.

## Verification

- cross-platform policy tests lock required inputs, unsigned naming, per-user
  scope, HKCU uninstall metadata, client-only payload, safe uninstall guard,
  CRC, Unicode, DPI, Windows support, and version metadata;
- Windows CI compiles with pinned NSIS, confirms `NotSigned`, installs silently,
  checks installed runtime/version/registry state, uninstalls silently, checks
  program/registry removal, and proves account-local data preservation;
- manifest tests reject a wrongly named installer and record only the exact
  unsigned NSIS Setup hash/size;
- a public release remains blocked on signed/timestamped installer and native
  Windows 10/11 install/launch/upgrade/uninstall evidence.
