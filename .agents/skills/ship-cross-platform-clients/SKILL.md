---
name: ship-cross-platform-clients
description: Guide implementation and release of this project's supported Windows Qt desktop and Vue Web clients. Use for Qt Widgets or QML architecture, local SQLite or IndexedDB, client sync/cache behavior, Windows integration, accessibility, installers, signing, automatic updates, Web deployment, CI packaging, or client support-scope changes.
---

# Ship Supported Web and Windows Clients

## Select the Client Path

Read `/AGENTS.md`, sections 11-12 of `/docs/architecture/README.md`, ADR-0009,
and `/docs/architecture/SUPPORT_MATRIX.md`.

Default choices:

- retain Qt 6/C++ for the supported Windows desktop client;
- extract application services and view models before migrating redesigned
  Widgets screens incrementally to QML;
- retain Vue 3 for Web and write new/refactored modules in TypeScript;
- share generated protocol bindings, semantic design tokens, terminology,
  fixtures, and behavior, not Windows shell or browser presentation code;
- treat macOS, Linux, Android, and iOS as unsupported client products unless a
  later ADR adds an owned compatibility and release plan.

A development or CI host is not automatically a product target. macOS may be
used for local feedback and Ubuntu for server/headless tests without creating a
client support promise.

## Preserve Client Layers

Route dependencies in this direction:

```text
View -> ViewModel -> Application service -> Repository/Sync service
                                      -> Transport/Database/Platform adapter
```

Keep durable messages, cursors, drafts, pending sends, and cached conversations
in SQLite on Windows desktop or IndexedDB on Web. Keep Pinia and QML properties
focused on active view state. Reconcile optimistic messages by
`clientMessageId` and server IDs.

Keep Windows tray, notifications, startup, shortcuts, title bars, file dialogs,
installer, and updater behavior behind platform adapters. Keep browser storage,
navigation, notifications, accessibility, and deployment behavior behind Web
boundaries. Do not introduce unsupported native-platform release work without a
support-scope ADR.

## Package and Deploy Supported Clients

For Windows:

1. build Release on native Windows CI;
2. run `windeployqt` and include non-Qt runtime dependencies;
3. create a signed and timestamped installer;
4. add MSIX only as an additional channel when justified;
5. verify clean install, upgrade, launch, local-data preservation, uninstall,
   and signatures on each supported Windows version.

For Web:

1. install from the committed lockfile and run tests plus a production build;
2. test the documented browser/version matrix;
3. deploy immutable versioned assets over HTTPS with deliberate CSP, caching,
   and source-map policy;
4. expose release health signals and retain fast rollback without rebuilding;
5. verify protocol compatibility, slow-network behavior, and storage migration.

macOS/Linux builds may be retained as explicitly labeled development or
portability checks. They do not produce supported client releases, and macOS
signing/notarization or Linux packaging is outside the current roadmap.

## Ship Updates Safely

Use a signed Windows update manifest with architecture, channel, version,
minimum compatible version, hash, signature, and URL. Provide stable and beta
channels, staged rollout, protocol compatibility checks, and rollback. Never put
signing credentials in the repository or build logs.

Use immutable Web asset versions and a deployment pointer or equivalent routing
mechanism so rollback does not require rebuilding an old revision.

## Verify Experience

Test keyboard navigation, scaling, light/dark themes, offline startup, reconnect,
slow networks, long histories, notifications, file flows, and local-data
upgrade. Build the Web production bundle and test Windows artifacts on native
Windows. Update release documentation with every packaging, deployment, browser
matrix, or updater change.
