# Desktop Build and Support Matrix

## M0 Decision

The existing Qt desktop client remains the native Windows and macOS client.
Java is planned for the server in M3; it does not replace the desktop UI
toolkit. The web client remains Vue and is built independently.

M0 standardizes new desktop verification on Qt 6.11.1. Qt 6.11 officially
supports Windows 10/11 with MSVC 2022, macOS 13 or newer (including macOS 26)
with Xcode 15 or newer, and Ubuntu 24.04. The repository's CI lanes are narrower
than the full Qt support list so failures are attributable to a small, explicit
toolchain set.

## Build Matrix

| Product | Verification host | Architecture/toolchain | Qt policy | M0 output |
| --- | --- | --- | --- | --- |
| Qt server and desktop client | Windows Server 2025 runner | x86_64, MSVC 2022 | pinned Qt 6.11.1 | unsigned, DLL-complete verification artifact |
| Qt server and desktop client | macOS 15 runner | runner-native Clang | pinned Qt 6.11.1 | unsigned app/server verification artifact |
| Qt server and desktop client | Ubuntu 24.04 runner | x86_64, distro GCC | Ubuntu Qt 6 packages | Release compilation gate |
| Web client | Ubuntu runner | Node.js 22 | lockfile + `npm ci` | production `dist` build gate |
| Headless V1 server tests | Ubuntu 24.04 runner | x86_64, distro GCC | Ubuntu Qt 6 packages | schema and TCP smoke gates |

The macOS development machine is the fastest local feedback path, but it is not
used to cross-compile Windows. Windows-specific linking and `windeployqt` run on
a native Windows CI runner. Likewise, the macOS lane catches Apple SDK and app
bundle issues that a Windows runner cannot represent.

## Artifact Status

The Windows and macOS artifacts produced by M0 are short-lived build evidence.
They prove that a clean checkout compiles and that required Qt runtime libraries
can be assembled. They are deliberately not presented as public releases:

- they are unsigned;
- the macOS artifact is not notarized or stapled;
- there is no installer, upgrade policy, or uninstall behavior;
- there is no signed automatic-update manifest.

Signed Windows installers, macOS Developer ID signing/notarization and DMG
creation remain M4 deliverables. Keeping that boundary explicit prevents an
unsigned CI ZIP from being mistaken for a trustworthy user installation path.

## Local macOS Policy

Use a Qt build that supports the active macOS SDK. Qt's online installer is the
preferred way to install pinned SDKs side by side. Homebrew is acceptable for
fast local work, but its Qt version can differ from CI.

Set `QMAKE` explicitly when another package manager shadows Qt 6:

```bash
QMAKE=/opt/homebrew/bin/qmake6 python3 tools/verify_m0.py --all
```

Do not edit Qt `.prl` files or delete framework references to force a link. If
the local Qt build is incompatible with the active Xcode SDK, keep running the
headless/Web checks locally and use the pinned macOS CI lane until the local Qt
SDK is upgraded.

## Review Policy

Review the pinned Qt version and runner images at least once per milestone and
before a release. A version change must update this matrix and CI in the same
commit, then pass the full native jobs. Adding Windows ARM64, Intel-specific
macOS output, or Linux packages is a support-scope decision, not an incidental
matrix expansion.

## Upstream References

- [Qt 6.11 supported platforms](https://doc.qt.io/qt-6/supported-platforms.html)
- [Qt installation options](https://doc.qt.io/qt-6.11/get-and-install-qt.html)
- [`install-qt-action` v4 usage](https://github.com/jurplel/install-qt-action)
- [GitHub-hosted runner images](https://github.com/actions/runner-images)
