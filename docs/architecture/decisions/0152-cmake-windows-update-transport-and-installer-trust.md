# ADR-0152: CMake Windows Update Transport and Installer Trust

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

After ADR-0151, signed-manifest trust had a CMake boundary, but discovery and
Setup download still compiled separately in qmake tests. Installer integrity and
Windows Authenticode verification were also directly listed by each consumer.
Combining all three into trust-core would allow untrusted network bytes and
platform process policy to leak into the portable signature/decision layer.

## Decision

- Build manifest/signature discovery and Setup download as
  `chatroom_windows_update_transport`, depending only on Qt Core/Network.
- Build size/SHA-256, Authenticode/RFC 3161/publisher verification, and locked
  verify-before-launch as `chatroom_windows_installer_trust`, depending on Qt
  Core plus `wintrust` and `crypt32` only on Windows.
- Preserve the order boundary: transport returns untrusted bytes/files; only the
  appropriate trust library may promote them.
- Register the three existing focused tests as bounded `m4_update_*` CTests.
- On non-Windows hosts, accept only portable integrity evidence and require the
  verifier/launch paths to report `UnsupportedPlatform`; never interpret that as
  positive Authenticode evidence.
- Keep native Windows qmake, unsigned rejection, protected signing, final-byte,
  and clean-host gates until equivalent CMake product evidence exists.

## Consequences

The CMake graph now reflects the updater's trust stages. Discovery proves exact
same-origin manifest/signature sequencing, no redirects/cache/compression, and
bounds. Setup download proves HTTPS-only bounded private staging and cleanup on
length, redirect, cancellation, destruction, or other failure. Installer trust
proves portable integrity everywhere and platform refusal off Windows; positive
publisher/timestamp verification remains Windows-only.

## Migration and Rollback

Preparation/check orchestration may link transport, trust-core, and installer-
trust without copying sources. Reverting these CMake targets changes no update
state or qmake product output. Do not remove native Windows coverage based on
macOS/Ubuntu portability results.

## Verification

- execute manifest fetch, installer download, and installer trust tests through
  CTest with 30-second bounds;
- reject unsafe URLs, credentials, queries, redirects, oversized/short bytes,
  cancellation, retained partials, digest/size mismatch, and unsigned launch;
- current macOS unified result: 19/19 CTests plus real server health pass;
- Windows positive Authenticode/timestamp/signature evidence remains an external
  M4 product gate.
