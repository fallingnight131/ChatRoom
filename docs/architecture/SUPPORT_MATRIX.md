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
| Windows desktop | Windows 10 22H2 (19045), Windows 11 23H2 (22631) and 24H2 (26100), x86_64; public claim still gated | Qt 6.11.1, MSVC 2022, native Windows CI | unsigned, DLL-complete verification artifact; protected candidate policy | signed and timestamped `Setup.exe`, per-target clean-host install/launch/real-upgrade/data/uninstall evidence, signed updater |
| Web | current + previous stable desktop Chrome, Edge, and Firefox (public claim still gated) | Node.js 22; locked Playwright 1.62.0 with Chromium 151 and Firefox 153 engine gates | production build, response-policy artifact, local browser smoke, reviewed production and branded-browser workflow definitions (no real runs claimed) | branded browser matrix, real production HTTPS/WSS/API completion, staged health and rollback evidence |

The Web engine gate proves startup, required capabilities, endpoint isolation,
and narrow login layout in the Playwright builds named above. It does not yet
prove the branded current/previous Chrome, Edge, and Firefox release matrix,
authenticated/offline/media behavior, or a production deployment. Safari,
WebKit, and mobile browsers are not currently claimed. Likewise, the current
Windows Server CI artifact proves native compilation and
dependency assembly, not Windows 10/11 clean-host compatibility. Formal public
support begins only after the M4 install, launch, upgrade, and uninstall matrix
passes on the named target versions.

The authoritative Web browser slots are listed in
[`packaging/web/browser-support-policy.json`](../../packaging/web/browser-support-policy.json).
ADR-0216 requires every schema-3 branded-browser record to identify the exact binary
version and digest and bind its smoke results to one immutable candidate. The
checks include keyboard traversal, announced validation errors, offline login
pause, and explicit recovery state. ADR-0210 pairs these records with an exact
six-slot completion boundary, but no
six-browser run or public support claim has been completed yet.

ADR-0211 also defines a repeated static/API/WebSocket health-window contract.
It is not production evidence until the release workflow collects and closes
real preview and production windows.

The authoritative initial Windows target list is
[`packaging/windows/support-matrix-policy.json`](../../packaging/windows/support-matrix-policy.json).
Each gate must report ProductType 1; Windows Server build agents cannot satisfy
it. ADR-0201 defines the independently verified per-host evidence contract.

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
the installer payload includes its pinned libsodium DLL. Ordinary verification
builds keep an empty trusted-key ring and instantiate no network path. Only an
explicit compiled-trust release build may enable the product controller.

An inactive decision policy now validates the signed object/schema,
architecture, UTC window, per-channel replay state, versions, deterministic
rollout, and installer metadata. There is still no durable replay/device state,
trusted product key, downloader, Authenticode check, scheduler, launch, or UI.

An inactive installer verifier now checks local size/SHA-256 and, on Windows,
WinTrust chain/revocation, counter-signature presence, and the signed publisher
certificate thumbprint. Native CI is configured to require unsigned rejection,
not acceptance of a real signed/timestamped Setup. No product downloader or
launcher invokes it.

The update state repository preserves a device UUIDv4 and stable/beta
sequence-plus-digest replay watermarks through locked atomic owner-only writes.
Malformed state fails closed. Enabled builds derive a dedicated `state`
directory under AppLocalData, separate from install lifecycle evidence.

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
is sufficient for final locked re-verification and is consumed by the packaged
helper only in an explicitly configured product build.

The asynchronous Windows handoff stages the helper and matching Qt
Core outside the installed directory and returns quit authorization only after
the helper has opened the current parent, lifecycle state is durable, and the
UUID commit event is signaled. Configured product UI instantiates it only after
default-No consent; successful signed install and restart remain unproven.

The Windows entry point now activates only post-restart reconciliation. It uses
owner-local derived directories, consumes a valid UUID-bound result once,
requires the running version to match reported success, and exits before login
while a recent result is pending so it cannot obstruct Setup. Discovery,
consent, and installation are reachable only with valid compiled product trust;
real signed restart/dialog behavior is unproven.

The client also has a compiled product-trust boundary. Ordinary and unsigned CI
builds remain disabled; a release build must explicitly provide an exact
stable/beta HTTPS manifest URL and one or two reviewed Ed25519 public keys.
Writable settings cannot enable or redirect trust. No production values are
currently provisioned.

The helper handoff now has a second UUID commit event. Ready proves only that the
helper owns the parent wait; the client must atomically persist pending lifecycle
state and signal commit before quit is authorized. Missing commit aborts without
starting Setup. This closes the persistence-failure race, but real signed install
and restart evidence remains absent.

For explicitly configured builds, the product controller now performs one
first-login automatic check and exposes a manual Help action. It permits
cancellation during preparation, asks default-No consent only after Setup passes
manifest and Authenticode trust, deletes declined/failed prepared files, and
uses the existing draft-flush/disconnect quit path only after committed handoff.
This is reachable product composition, not native Windows release evidence;
production keys, signed Setup acceptance, and restart remain unverified.

A provider-neutral native signature probe now defines the positive release
contract for the client, update helper, standalone uninstaller, and final Setup:
exact names, valid timestamped Authenticode, one reviewed publisher-certificate
SHA-256, and atomic
final-byte evidence. Current CI exercises only its unsigned rejection path and
does not satisfy the Windows support gate.

The Windows-produced JSON must then pass an independent cross-platform verifier
that recomputes final client/helper/uninstaller/Setup hashes and enforces exact
identity, schema, signer, timestamp, and freshness. Fixture success validates the parser,
not Windows signatures or product support.

After independent evidence verification, the complete `windeployqt` payload is
assembled with Setup and evidence into one immutable candidate. Required Qt
Core/platform, SQLite, and libsodium runtimes are closed by a sorted file
manifest; server/debug/key/environment files are rejected. Candidate assembly
also requires a closed native install/uninstall record that binds installed
client/helper/uninstaller bytes, signatures, registration, and cleanup to the
four signed source files. The policy and parser are implemented, but they do not
satisfy clean-host or public support gates until the protected Windows runner
produces positive evidence.

The Windows client now owns a session-local liveness mutex and refuses a second
instance. NSIS checks the same mutex before mutation and returns 4 for a silent
running-client attempt; native CI is configured to prove the current install and
AppData remain unchanged. Cross-session/arbitrary locks and native graceful
update shutdown/launch evidence remain release work.

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
