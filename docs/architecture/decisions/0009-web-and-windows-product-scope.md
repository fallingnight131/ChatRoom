# ADR-0009: Web and Windows Product Support Scope

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M2-M4
- Supersedes: [`ADR-0002`](0002-client-and-distribution-strategy.md)

## Context

The repository contains a substantial Qt Widgets desktop client and a Vue Web
client. The current desktop implementation is primarily exercised on Windows,
while the active product need is limited to browser users and Windows desktop
users.

Treating macOS and Linux builds as supported products would also require owned
compatibility matrices, native UX work, installers, signing or notarization,
upgrade/uninstall tests, update channels, and ongoing user support. That cost
would compete with the higher-priority M1 reliability and M2 client-data work.

The maintainer currently develops on macOS, and Ubuntu remains useful for
headless server and protocol tests. Development or CI host choice must therefore
be kept separate from client product support.

Affected quality attributes are delivery focus, user experience, compatibility,
operability, release security, and future portability. This decision changes
distribution scope only; it does not change the V1/V2 protocol, durable data,
server deployment topology, or supported-client compatibility window.

## Decision

- The supported client products are the Vue Web client and the Qt 6 Windows
  desktop client.
- The initial Windows desktop baseline is Windows 10/11 on x86_64 with MSVC
  2022 and pinned Qt 6.11.1. A Windows architecture/toolchain expansion requires
  an explicit support-matrix update.
- The Web client remains Vue 3 and moves incrementally toward TypeScript,
  feature modules, and IndexedDB-backed local data. A browser/version matrix
  must be pinned before public Web compatibility is claimed.
- The Windows client remains Qt 6/C++. Application, synchronization,
  persistence, and Windows platform integration stay behind explicit
  boundaries; redesigned screens may move from Widgets to QML incrementally.
- Windows is distributed through a signed and timestamped installer, with an
  optional MSIX/Store channel only after the direct channel is stable. Updates
  use a signed manifest with staged rollout and rollback.
- Web delivery uses versioned assets, HTTPS/WSS, deliberate CSP/cache/source-map
  policy, health checks, staged rollout, and rollback without rebuilding.
- macOS, Linux, Android, and iOS native clients are outside the supported product
  and release scope. Adding one requires a later ADR and an owned compatibility,
  UX, packaging, security, update, and support plan.
- macOS and Linux may remain development, CI, server, or portability-check
  environments. Their build results must be labeled non-product evidence and
  must not be presented as supported client releases.
- Portable shared code may be retained where inexpensive, but unsupported
  platform work is not a release gate and must not delay Web or Windows work.

## Alternatives Considered

- Continue promising Windows, macOS, and Linux desktop clients: rejected for the
  current roadmap because packaging, system integration, and compatibility cost
  would dilute reliability and user-experience work on the required products.
- Ship only the Web client: rejected because the existing Qt Windows client
  provides valuable native performance, filesystem, notification, and desktop
  integration capabilities.
- Replace Qt with a Web desktop wrapper now: rejected because it creates a
  rewrite without solving the higher-priority messaging and offline-data gaps.
- Delete macOS/Linux-compatible code and checks immediately: rejected because
  the maintainer develops on macOS and low-cost portability checks can still
  expose shared defects without creating a support promise.

## Consequences

- Release engineering, UX validation, accessibility, and support effort can
  focus on Web and Windows.
- M4 no longer contains macOS signing/notarization or Linux packaging work; it
  adds explicit Web deployment and rollback gates.
- macOS and Linux client behavior may regress without blocking a supported
  release. Reintroducing either platform later may therefore require additional
  remediation and migration work.
- CI names, artifacts, documentation, and issue triage must distinguish product
  gates from development/portability evidence.
- The Java backend and its deployment operating system remain independent of
  the supported client list.

## Migration and Rollback

This is a documentation, governance, and delivery-scope change. Existing source
compatibility and dated M0 evidence are retained; no user data or protocol is
migrated and no supported V1 client is disconnected.

Architecture, roadmap, support matrix, engineering instructions, and CI labels
are updated together. macOS/Linux client artifacts must no longer be described
as releases. Rolling back this decision requires accepting a new ADR that names
the restored platform, owner, compatibility window, native test matrix,
packaging/signing strategy, update path, and operating cost.

## Verification

- architecture, roadmap, support matrix, engineering instructions, and client
  shipping skill consistently name Web and Windows as the product clients;
- Web changes pass clean install, tests, production build, supported-browser
  automation once pinned, and deployment rollback checks;
- Windows desktop changes pass a native Release build; M4 additionally requires
  signature, clean install, launch, upgrade, local-data preservation, uninstall,
  updater, and rollback evidence;
- macOS/Linux jobs and artifacts, if retained, are labeled development,
  portability, server, or test evidence rather than client releases;
- protocol compatibility remains tested for supported old/new Web and Windows
  clients throughout documented migration windows.
