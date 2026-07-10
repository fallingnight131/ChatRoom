# M0 Acceptance Record — 2026-07-11

## Decision

M0 repository scope is complete. The project now has an implementation-derived
baseline, change governance, reproducible verification, critical V1 integration
coverage, native product build automation, and a stored performance reference.
M1 security/reliability work can begin without mixing a Java rewrite or UI
redesign into the baseline milestone.

This decision does not claim that the V1 system is production-scale or secure.
Those are explicit later milestones.

## Delivered Commit Sequence

Each independently verified step was committed as required by `AGENTS.md`:

| Commit | Outcome |
| --- | --- |
| `6f2b86f` | Architecture governance, ADR process, roadmap, and project skills |
| `8622418` | Code-derived V1 protocol and data baselines |
| `18866be` | Architecture/data documentation tracking correction |
| `84cf9b0` | Reproducible inventory/Web/Qt verification entry point |
| `238927e` | Deterministic first-start SQLite schema fix |
| `d230d49` | Clean/restart SQLite schema CI regression |
| `b5d8d68` | Critical V1 room-flow TCP integration coverage |
| `aa3b80b` | V1 network smoke execution in CI |
| `f62beb5` | Friendship and direct-message integration coverage |
| `ac26baa` | Linux/macOS/Windows Qt Release build automation and support matrix |
| `9014581` | Reproducible V1 performance harness and first stored result |

## Final Local Verification

Host:

- Apple Silicon arm64, macOS 26.5.1;
- Xcode 26.3 / macOS 26.2 SDK;
- Homebrew Qt 6.9.0 for headless Qt checks;
- Node.js/npm selected from the local development environment.

Command:

```bash
python3 tools/verify_m0.py --web --db-schema --v1-smoke --performance
```

Results:

| Check | Result |
| --- | --- |
| V1 source inventory | Passed: 120 message types and 13 SQLite tables match the stored inventory |
| Web clean install/build | Passed: 61 lockfile packages installed and Vite built 67 modules |
| SQLite first start/restart | Passed: required schemas are complete, identical, and pass integrity check |
| V1 TCP integration | Passed: registration, login, room, history, file metadata, reconnect, recall, friendship, and direct chat |
| Performance harness | Passed: generated a complete JSON result with latency, SQLite, throughput, CPU/RSS, connection, and size fields |
| Diff hygiene | Passed: workflow/JSON parsing, inventory check, and `git diff --check` |

The reviewed first performance result remains
[`M0_PERFORMANCE_2026-07-11.json`](M0_PERFORMANCE_2026-07-11.json); the final
acceptance rerun wrote its transient output below ignored `build/` and did not
replace the reviewed result.

## Native Product Build Boundary

The local Qt 6.9.0 distribution compiles product sources but its `.prl` metadata
references Apple's removed `AGL.framework`, so the full desktop/server link does
not succeed with the active macOS 26 SDK. The earlier evidence is retained in
[`M0_VERIFICATION_2026-07-10.md`](M0_VERIFICATION_2026-07-10.md).

An attempt to upgrade the local Homebrew toolchain to Qt 6.11.1 on 2026-07-11
was stopped by repeated network resets while downloading from GHCR. Homebrew
remained on a coherent Qt 6.9.0 installation; no repository or application data
was changed by the failed download.

The repository-side resolution is complete:

- Qt 6.11.1 is pinned for native Windows and macOS CI;
- Ubuntu 24.04 verifies the full Qt server/client source with distro Qt 6;
- Windows uses MSVC 2022 and `windeployqt`;
- macOS uses a native runner and `macdeployqt`;
- both platforms upload short-lived unsigned verification artifacts.

These native jobs have not run from the local-only commits because this task did
not push to GitHub. Their first successful run is required before calling a
specific commit cross-platform green. A failure there reopens the M0 delivery
baseline; it must not be deferred into M1.

## Explicit Non-Goals

- Unsigned CI artifacts are not end-user installers.
- Windows signing/installers and macOS signing/notarization/DMG remain M4.
- The loopback performance result is not a production capacity claim.
- Java V2 implementation remains M3; M1 first fixes V1 security and reliability.
- Qt Widgets-to-QML evolution and offline client storage remain M2.

## M1 Entry Gate

The next work should start with the M1 threat and compatibility slices: browser
credential storage, password hashing migration, server-side authorization,
inbound/outbound limits, and idempotent message submission. Every slice must
retain the V1 smoke suite and update the protocol/data baseline intentionally.
