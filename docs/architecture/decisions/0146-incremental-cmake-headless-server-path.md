# ADR-0146: Incremental CMake Headless Server Path

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The Qt V1 server, Windows client, updater launcher, and tests are built by many
qmake project files. The M4 roadmap calls for movement toward CMake when build
architecture is touched, but replacing every target at once would combine
server equivalence, Windows deployment, installer inputs, test discovery, and
platform dependency resolution in one risky migration. The Java V2 workspace
and generated C++ protocol bindings already use Gradle/CMake, so retaining qmake
as the only V1 build model also increases future integration cost.

## Decision

- Add a root CMake 3.24 project with one initially enabled target:
  `ChatServerHeadless`.
- Compile the exact Common and Server production sources already listed by
  `Tests/HeadlessServer.pro`; do not fork or wrap their implementation.
- Require C++17 without compiler extensions, Qt 6.5 or later Core, Network, SQL,
  and WebSockets, CMake AUTOMOC, and libsodium.
- Resolve libsodium from normal CMake search paths or a cache/environment
  `SODIUM_ROOT`. Do not execute a package manager or download dependencies from
  configuration.
- Preserve the headless compile definitions that disable image thumbnails and
  enable benchmark metrics.
- Add `verify_m0.py --cmake-headless` to configure a Release tree, build only
  this target, and run the real V1 HTTP health process test. Run that gate on the
  Ubuntu baseline and allow it on macOS development hosts.
- Keep qmake authoritative for the supported Windows client, updater, installer
  payload, Qt unit tests, and existing server product build until each target
  has its own equivalence and packaging evidence. Do not relabel the new path as
  a Windows product artifact.

## Alternatives Considered

- Convert every Qt target now: rejected because installer and Windows-native
  behavior would be changed without clean-host evidence.
- Keep qmake indefinitely: rejected because it delays dependency and generated-
  protocol integration and makes the eventual migration larger.
- Create a second copy of the server sources under a CMake directory: rejected
  because the two implementations would drift.

## Consequences

The repository now has a small CMake seam that proves the current V1 server can
be represented without qmake-specific source semantics. Source-list duplication
between `HeadlessServer.pro` and `CMakeLists.txt` is temporary and guarded by
compilation plus a real process test, but full behavioral equivalence still
comes from the existing V1 smoke suite. Windows product packaging remains
unchanged.

## Migration and Rollback

Future slices may migrate focused libraries/tests and finally the Windows
targets only after native packaging equivalence. Removing `CMakeLists.txt`, the
verification option, and its CI step restores the prior build path without
changing application data, protocol, or qmake output.

## Verification

- configure and build Release on macOS with an explicit Homebrew
  `SODIUM_ROOT`;
- configure/build on Ubuntu CI using distribution Qt/libsodium search paths;
- start the resulting server and verify exact `/api/health` success and negative
  paths;
- keep the inventory gate before configuration so source drift cannot be hidden
  by the second build system.
