# ADR-0002: Qt Desktop, Vue Web, and Native Desktop Packaging

- Status: Accepted
- Date: 2026-07-10
- Owners: project maintainers
- Related milestone: M2-M4

## Context

The repository already contains a substantial Qt Widgets desktop client and a
Vue web client. Replacing both while replacing the server would multiply product
risk. Qt remains suitable for Windows, macOS, and Linux, but the current desktop
UI, networking, and application state need clearer boundaries.

Desktop users also need trusted installers, native platform integration, and a
safe update path rather than raw executable archives.

## Decision

- Retain Qt 6/C++ for desktop.
- Extract application, synchronization, persistence, and platform services from
  Widgets before migrating redesigned screens incrementally to QML.
- Retain Vue 3 for web and move new code toward TypeScript, feature modules, and
  IndexedDB-backed local data.
- Generate protocol bindings for C++, Java, and TypeScript from one source.
- Distribute Windows primarily through a signed installer, with optional MSIX.
- Distribute macOS as a Developer ID-signed, hardened, notarized DMG.
- Distribute Linux initially as AppImage.
- Build, sign, and test desktop artifacts on native CI runners.

## Alternatives Considered

- JavaFX desktop: rejected because it discards the mature Qt client and offers no
  backend-language requirement or decisive product advantage.
- One Flutter client for every platform immediately: rejected due to rewrite
  cost; it remains an option for future mobile clients.
- Web wrapper for desktop: rejected as the default because current Qt system
  integration and native performance are valuable.

## Consequences

- Desktop and web keep separate presentation code but share protocol, product
  semantics, fixtures, and design tokens.
- QML migration is incremental rather than a release-blocking rewrite.
- Signing certificates, Apple notarization, native CI, and updater security
  become ongoing release responsibilities.

## Migration and Rollback

Introduce view models and local repositories behind existing Widgets first.
Migrate one screen at a time while retaining the same application services.
Keep the last known-good signed installer and update manifest available for
rollback.

## Verification

- clean install, launch, login, message, upgrade, and uninstall on Windows and
  macOS;
- local database preservation across upgrades;
- signature and notarization verification;
- old/new client protocol compatibility;
- offline, reconnect, and optimistic-message reconciliation tests.

