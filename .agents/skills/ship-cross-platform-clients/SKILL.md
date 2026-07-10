---
name: ship-cross-platform-clients
description: Guide implementation and release of this project's Qt desktop and Vue web clients. Use for Qt Widgets or QML architecture, local SQLite or IndexedDB, client sync/cache behavior, Windows/macOS/Linux integration, accessibility, installers, code signing, notarization, automatic updates, CI packaging, or cross-platform release work.
---

# Ship Cross-platform Clients

## Select the Client Path

Read `/AGENTS.md`, sections 11-12 of `/docs/architecture/README.md`, and
ADR-0002.

Default choices:

- retain Qt 6/C++ for Windows, macOS, and Linux desktop;
- extract application services and view models before migrating redesigned
  Widgets screens incrementally to QML;
- retain Vue 3 for web and write new/refactored modules in TypeScript;
- share generated protocol bindings, semantic design tokens, terminology,
  fixtures, and behavior—not platform window code.

## Preserve Client Layers

Route dependencies in this direction:

```text
View -> ViewModel -> Application service -> Repository/Sync service
                                      -> Transport/Database/Platform adapter
```

Keep durable messages, cursors, drafts, pending sends, and cached conversations
in SQLite on desktop or IndexedDB on web. Keep Pinia and QML properties focused
on active view state. Reconcile optimistic messages by `clientMessageId` and
server IDs.

Keep tray/menu bar, notifications, startup, shortcuts, title bars, file dialogs,
and updater behavior behind OS adapters. Follow Windows and macOS conventions
instead of forcing identical behavior.

## Package Natively

For Windows:

1. build Release on Windows;
2. run `windeployqt` and include non-Qt runtime dependencies;
3. create a signed, timestamped installer;
4. add MSIX only as an additional channel when justified;
5. verify clean install, upgrade, launch, local-data preservation, and uninstall.

For macOS:

1. build on macOS for the supported architectures;
2. run `macdeployqt`;
3. sign nested code and the app with Developer ID and hardened runtime;
4. create the DMG, submit with current Apple notarization tooling, and staple the
   ticket;
5. verify Gatekeeper launch and upgrade behavior on a clean machine.

For Linux, start with AppImage and add deb/rpm/Flatpak only with a maintenance
plan. For every platform, verify current Qt and platform-vendor requirements
before changing release tooling.

## Ship Updates Safely

Use a signed update manifest with platform, architecture, channel, version,
minimum compatible version, hash, signature, and URL. Provide stable and beta
channels, staged rollout, protocol compatibility checks, and rollback. Never put
signing credentials in the repository or build logs.

## Verify Experience

Test keyboard navigation, scaling, light/dark themes, offline startup, reconnect,
slow networks, long histories, notifications, file flows, and local-data upgrade.
Build the web production bundle and test desktop artifacts on native operating
systems. Update release documentation with every packaging or updater change.
