# Client Product Support and Build Matrix

## Current Product Scope

The supported client products are:

- the Vue Web client;
- the Qt 6 Windows desktop client.

macOS, Linux, Android, and iOS are not supported client products. They have no
release compatibility promise, installer, signing path, update channel, or user
support obligation. Adding any of them requires a new support-scope ADR with an
owned build, test, packaging, security, and update plan. This scope is recorded
in [`ADR-0009`](decisions/0009-web-and-windows-product-scope.md).

Development and CI hosts are deliberately separate from product support. A
macOS development build or an Ubuntu compile/test job is useful engineering
evidence, but it does not make that operating system a supported client.

## Supported Client Matrix

| Product | Committed target | Toolchain policy | Current evidence | M4 release gate |
| --- | --- | --- | --- | --- |
| Windows desktop | Windows 10/11, x86_64 | Qt 6.11.1, MSVC 2022, native Windows CI | unsigned, DLL-complete verification artifact | signed and timestamped `Setup.exe`, install/upgrade/uninstall checks, signed updater |
| Web | current + previous stable desktop Chrome, Edge, and Firefox (public claim still gated) | Node.js 22; locked Playwright 1.62.0 with Chromium 151 and Firefox 153 engine gates | production build, versioned response-policy artifact, local two-engine browser smoke | branded browser matrix, production HTTPS/WSS/API observation, staged health and rollback evidence |

The Web engine gate proves startup, required capabilities, endpoint isolation,
and narrow login layout in the Playwright builds named above. It does not yet
prove the branded current/previous Chrome, Edge, and Firefox release matrix,
authenticated/offline/media behavior, or a production deployment. Safari,
WebKit, and mobile browsers are not currently claimed. Likewise, the current
Windows Server CI artifact proves native compilation and
dependency assembly, not Windows 10/11 clean-host compatibility. Formal public
support begins only after the M4 install, launch, upgrade, and uninstall matrix
passes on the named target versions.

The Windows desktop client remains Qt 6/C++. Java is planned for the backend in
M3 and does not replace the Windows UI toolkit. New or substantially redesigned
desktop screens may move incrementally from Widgets to QML after application,
synchronization, and persistence boundaries are extracted.

## Non-product Verification Environments

| Environment | Allowed purpose | Not evidence of |
| --- | --- | --- |
| macOS development machine or CI | fast local feedback, server/toolchain checks, Qt portability regression | supported macOS app, DMG, signing, notarization, update compatibility |
| Ubuntu 24.04 CI | Web builds, headless server tests, SQLite/protocol regression, optional Qt portability compilation | supported Linux desktop app, AppImage/deb/rpm, desktop integration |

The current Qt server also builds on several host operating systems. Server
deployment support is an operations decision and is independent of which client
operating systems are supported.

## Windows Build and Release Policy

Windows product verification runs natively with Windows Server 2025, x86_64
MSVC 2022, and pinned Qt 6.11.1. The build uses `windeployqt` and includes the
required client runtime libraries. The current server is still compiled as a
compatibility gate, but it is excluded from the client distribution payload.

The client-only verification payload records canonical version, Git revision,
toolchain, file sizes, and SHA-256 hashes in a deterministic manifest. It remains
short-lived build evidence only and is explicitly labeled unsigned. Native CI
now compiles an NSIS Setup and exercises isolated silent install/uninstall plus
account-local data preservation. It is also configured to install a synthetic
predecessor, stage/swap the whole program directory, remove stale program files,
and retain AppData through upgrade. That predecessor uses current binaries and
does not prove a real cross-version schema/client transition. The Setup still
has no Authenticode signature, clean Windows 10/11 launch/upgrade matrix, or
signed automatic-update manifest and is not a supported release.

The repository now defines and tests a canonical detached-Ed25519 stable/beta
update manifest, including sequence, expiry, rollout, installer integrity, and
expected Authenticode signer. It has no product key and is a default-off
protocol foundation, not an update channel.

The Windows client now compiles a default-deny canonical Ed25519 verifier and
the installer payload includes its pinned libsodium DLL. Because the trusted key
ring is empty and no fetch/decision/install path calls it, this remains a local
primitive rather than an enabled updater.

An inactive decision policy now validates the signed object/schema,
architecture, UTC window, per-channel replay state, versions, deterministic
rollout, and installer metadata. There is still no durable replay/device state,
trusted product key, downloader, Authenticode check, scheduler, launch, or UI.

An inactive installer verifier now checks local size/SHA-256 and, on Windows,
WinTrust chain/revocation, counter-signature presence, and the signed publisher
certificate thumbprint. Native CI is configured to require unsigned rejection,
not acceptance of a real signed/timestamped Setup. No product downloader or
launcher invokes it.

An inactive state repository now preserves a device UUIDv4 and stable/beta
sequence-plus-digest replay watermarks through locked atomic owner-only writes.
Malformed state fails closed. No Windows AppData path or updater service creates
this state in the product.

An inactive application service now enforces signature verification, durable
state, semantic decision, and atomic replay acceptance in that order. Its
trusted key ring and product state path remain unconfigured, with no manifest
transport, scheduler, UI, or installer launch.

An inactive dedicated installer transport now enforces credential-free HTTPS,
no redirects, an aligned 2 GiB signed/streamed limit, timeout/cancel, private
partial staging, and failure cleanup. It is not connected to a product endpoint,
scheduler, UI, or launcher.

An inactive preparation service now permits only signed `Eligible` policy to
download and exposes a file only after background installer trust succeeds.
Failure/cancellation removes bytes. Product keys and paths, a configured
discovery origin, consent UI, process launch, and install/rollback observation
remain absent.

An inactive manifest transport now fetches only the exact credential-free HTTPS
`manifest.json` plus same-origin `manifest.json.sig` pair, refuses redirects,
and bounds/discards unverified responses. No product origin, trusted key,
scheduler, UI, or product invocation is configured.

An inactive check service now composes that transport with signature/replay/
policy acceptance and verified installer preparation. Invalid trust and staged
deferral cannot reach Setup download. The composition has no product origin,
trusted key, AppData/staging configuration, scheduler, consent UI, or launcher.

The Windows verifier also has an inactive final-boundary operation that repeats
all installer trust checks while holding the file against replacement through
silent `CreateProcessW`, then observes its bounded exit. It has no external
product call path and has not accepted a real signed Setup in CI.

The payload now contains an external update helper that waits for a specifically
handshaken parent, invokes the locked boundary, records one-shot atomic evidence,
and restarts only after installer exit zero. Native CI is configured for its
unsigned rejection path. The client does not invoke it, and a successful real
signed upgrade remains an M4 release gate.

The inactive preparation/check APIs now hand off one typed installer value with
the verified path and exact signed size, digest, and publisher thumbprint. This
is sufficient for final locked re-verification but is still not connected to
the packaged helper or product lifecycle.

The Windows client now owns a session-local liveness mutex and refuses a second
instance. NSIS checks the same mutex before mutation and returns 4 for a silent
running-client attempt; native CI is configured to prove the current install and
AppData remain unchanged. Cross-session/arbitrary locks and graceful update
shutdown/launch remain release work.

M4 must provide:

- a signed and timestamped direct installer;
- clean install, launch, upgrade, local-data preservation, and uninstall tests;
- a signed update manifest with stable/beta channels and rollback;
- optional MSIX/Store distribution only after the direct channel is stable.

## Web Build and Release Policy

Web verification runs from the committed lockfile with `npm ci`, tests, and a
production Vite build. CI retains a short-lived, explicitly not-deployed
artifact with exact Git/package version, file hashes, local hashed entrypoints,
map-file/trailing-directive rejection, intended cache classes, and an exact
schema-2 CSP/HSTS/release-identity response contract. The artifact still labels
that policy `required-not-observed`. Isolated tests retain immutable versions,
atomically select one release, verify its identity and bytes, and rehearse
A-to-B-to-A rollback without rebuilding. Public delivery must additionally
use the provider-neutral HTTPS probe to observe selected bytes and the exact
bound headers. CI already runs that probe against an ephemeral trusted localhost
TLS server. Public delivery must additionally observe and prove:

- current and previous stable branded Chrome/Edge/Firefox coverage on the
  release candidate, plus deeper authenticated/offline/accessibility paths;
- production DNS/certificate validity, WSS/API reachability, and response
  headers/bytes matching the bound policy and release;
- immutable versioned assets and a traceable deployment identifier;
- health checks, staged rollout, monitoring, and rollback without rebuilding;
- compatibility with at least one previous supported client/server protocol
  version during migration windows.

## Local macOS Development Policy

The macOS development machine remains a valid fast-feedback host. Use a Qt build
compatible with the active macOS SDK and set `QMAKE` explicitly when several Qt
installations exist:

```bash
QMAKE=/opt/homebrew/bin/qmake6 python3 tools/verify_m0.py --qt
```

Do not patch installed Qt `.prl` files or remove framework references to force a
link. A successful macOS build is useful portability evidence only; failure does
not by itself block a Windows or Web release unless it also exposes a shared
source defect.

## Historical M0 Evidence

M0 created Windows and macOS unsigned artifacts plus an Ubuntu Release compile
gate before the supported product scope was narrowed. Those records remain
unchanged as historical evidence. Current workflow labels and documentation must
classify macOS/Linux results as development or portability checks, not releases.

## Review Policy

Review the Windows Qt/toolchain pins, Node.js version, browser matrix, and CI
runner images at least once per milestone and before a release. Expanding to
Windows ARM64 or any macOS, Linux, Android, or iOS client is a support-scope
decision and must not enter through an incidental build change.

## Upstream References

- [Qt 6.11 supported platforms](https://doc.qt.io/qt-6/supported-platforms.html)
- [Qt for Windows deployment](https://doc.qt.io/qt-6/windows-deployment.html)
- [Qt installation options](https://doc.qt.io/qt-6.11/get-and-install-qt.html)
- [`install-qt-action` v4 usage](https://github.com/jurplel/install-qt-action)
- [GitHub-hosted runner images](https://github.com/actions/runner-images)
