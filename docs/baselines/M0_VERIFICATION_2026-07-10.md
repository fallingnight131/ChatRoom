# M0 Verification Record — 2026-07-10

This is an environment/build baseline, not a performance benchmark.

## Source State

- Branch: `main`
- Baseline commits: `8622418`, `18866be`
- Host architecture: Apple Silicon arm64
- Host OS: macOS 26.5.1
- Xcode: 26.3
- Node.js: 23.11.0
- npm: 11.16.0
- Qt/qmake selected: Qt 6.9.0 from Homebrew

## Results

| Check | Result | Evidence |
|---|---|---|
| V1 inventory | Passed | 120 message types, 50 dispatched requests, 13 SQLite tables, 2 explicit indexes |
| `npm ci` | Passed | 61 packages installed from lockfile |
| Web production build | Passed | Vite transformed 67 modules and emitted `dist/` |
| Qt server qmake generation | Passed with SDK warning | Release Makefile generated |
| Qt client qmake generation | Passed with SDK warning | Release Makefile generated |
| Qt server link | Failed due local toolchain incompatibility | Homebrew Qt 6.9 PRL requires removed `AGL.framework` under macOS SDK 26.2 |
| Qt client link | Failed due local toolchain incompatibility | Same missing `AGL.framework` dependency |

Source compilation reached the link stage. `DatabaseManager.cpp` also produced
Qt 6.9 deprecation warnings for `QDateTime::setTimeSpec`; these are tracked as
compatibility debt and were not treated as the link failure.

## Interpretation

- The web release build is reproducible on this host.
- Inventory drift detection is working.
- The selected Qt distribution is not compatible with the active macOS SDK,
  independently of project source compilation.
- This record does not establish a supported macOS build or package. M4 requires
  a supported Qt/SDK pair, native packaging, signing, notarization, and clean-host
  tests.

## Follow-up

1. Add Qt compilation to CI on a pinned, supported Linux/Windows/macOS toolchain.
2. Select and document the supported Qt and OS version matrix.
3. Address Qt deprecations separately from toolchain provisioning.
4. Add executable integration smoke tests after a server build is available in
   CI.

