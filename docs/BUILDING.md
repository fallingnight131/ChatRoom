# Reproducible M0 Verification

The M0 verifier provides one entry point for source-inventory drift, the web
production build, and optional Qt release builds.

## Inventory

Requires Python 3.9 or newer:

```bash
python3 tools/verify_m0.py
```

This compares `Common/Protocol.h`, `ChatServer.cpp`, and
`DatabaseManager.cpp` against `docs/baselines/v1-inventory.json`.

When an intentional protocol/schema/dispatch change is reviewed, regenerate the
baseline and include it in the same commit:

```bash
python3 tools/m0_inventory.py --write
python3 tools/m0_inventory.py --check
```

Never regenerate merely to hide unexpected drift.

## Web

Requires a supported Node.js release and npm:

```bash
python3 tools/verify_m0.py --web
```

The command runs `npm ci`, the Node regression tests, and `npm run build`. To
reuse already installed dependencies during local iteration:

```bash
python3 tools/verify_m0.py --web --skip-npm-ci
```

Install the exact Chromium/Firefox engines bound to locked Playwright 1.62.0 and
run the browser gate against the production build:

```bash
cd WebClient
npx playwright install chromium firefox
npm run test:browser
```

Linux CI uses `npx playwright install --with-deps chromium firefox` and one test
worker. The current bound engines are Chromium 151.0.7922.34 and Playwright
Firefox 153.0. They are engine-level evidence; branded current/previous Chrome,
Edge, and Firefox checks remain a public-release gate. Playwright reports and
test results are local/short-lived CI output and are ignored by Git.

The protected `.github/workflows/m4-web-browser-support-matrix.yml` gate uses
six dedicated x86_64 Linux hosts for current/previous branded Chrome, Edge, and
Firefox. Each host must expose one preinstalled non-symlink executable through
`CHATROOM_BRANDED_BROWSER_EXECUTABLE`; dispatch approval supplies its exact
reviewed version and SHA-256 plus the exact undeployed candidate artifact. The
workflow hashes the binary before and after Playwright, emits a candidate-bound
record, and verifies it with `tools/web_browser_host_evidence.py`. It does not
install browsers or mutate production. After all six hosts pass, a separate
Ubuntu job redownloads the exact candidate, closes the ordered records with
`tools/web_browser_matrix_completion.py`, independently reverifies the result,
and retains it for 90 days. See ADR-0208 through ADR-0210. The workflow has not
yet produced real browser or support evidence.

CI then copies the real Vite tree and `packaging/web/response-policy.json` into a
short-lived, explicitly undeployed Web verification artifact.
`tools/web_artifact_manifest.py` requires matching package/lock versions, local
content-hashed entrypoints, no source-map files or trailing map directives,
stable file hashes, and records `index.html` as `no-store` plus hashed assets as
one-year immutable. Schema 2 also binds the exact CSP/HSTS/cache/source-map and
release-identity policy while labeling it `required-not-observed`. Run its
policy tests with:

```bash
python3 Tests/web_artifact_manifest_test.py
```

The generated manifest is not evidence that a hosting service applied cache or
security headers, passed browser compatibility, or can roll back.

Exercise immutable staging, activation health, and no-rebuild rollback policy:

```bash
python3 Tests/web_release_store_test.py
python3 Tests/web_release_probe_test.py
python3 Tests/web_rollback_evidence_test.py
python3 Tests/web_application_route_probe_test.py
python3 Tests/web_promotion_evidence_test.py
```

The operator commands and filesystem layout are documented in
[`WEB_RELEASE_ROLLBACK.md`](deployment/WEB_RELEASE_ROLLBACK.md). This isolated
suite generates a one-day localhost certificate/key in a temporary directory,
observes exact HTTPS headers and bytes, and deletes the key after the test. It is
still intentionally separate from production-provider and browser verification.

The V1 smoke gate also starts the real Qt HTTP listener and verifies the exact,
query-free, credential-free `GET /api/health` routing contract. It is a process
and `/api/` reachability signal only, not database or attachment readiness.

Once an HTTPS reverse proxy owns both application paths, create separate
write-once route evidence without credentials:

```bash
python3 tools/web_application_route_probe.py \
  --base-url https://chat.example.com \
  --output /path/to/evidence/web-application-routes.json
```

The probe requires the exact V1 health body/headers and completes a fresh
nonce-bound RFC 6455 upgrade at `/ws`. Use `--websocket-path` only when it exactly
matches the reviewed path compiled into the Web release. This is route evidence,
not login, database, file, load, or continuous-availability evidence.

Observe candidate B and its routes on a dedicated preview HTTPS origin while
production still serves retained A. Bind both immutable releases before any
provider adapter changes production traffic:

```bash
python3 tools/web_promotion_evidence.py record \
  --release-root /srv/chat-room-web/releases/<candidate-id> \
  --release-observation /path/to/evidence/candidate-static.json \
  --route-observation /path/to/evidence/candidate-routes.json \
  --rollback-release-root /srv/chat-room-web/releases/<previous-id> \
  --rollback-observation /path/to/evidence/previous-static.json \
  --output /path/to/evidence/web-technical-promotion.json

python3 tools/web_promotion_evidence.py verify \
  --release-root /srv/chat-room-web/releases/<candidate-id> \
  --release-observation /path/to/evidence/candidate-static.json \
  --route-observation /path/to/evidence/candidate-routes.json \
  --rollback-release-root /srv/chat-room-web/releases/<previous-id> \
  --rollback-observation /path/to/evidence/previous-static.json \
  --output /path/to/evidence/web-technical-promotion.json
```

Schema 2 requires candidate static/routes to share the preview origin and the
rollback observation to name a different production origin. The production URL
is derived from observed A rather than accepted as an arbitrary mutation input.
Select staged B for the configured preview host with
`web_preview_release.py select`; its `preview-release.json` is independent from
production `active-release.json` and is revalidated after atomic replacement.
The hosting adapter must route preview and production through their respective
selectors. See ADR-0206.
The default candidate observation freshness is 15 minutes and both current
observations must be within five minutes of one another. The record is labeled
not-published and performs no provider mutation.

After a protected `web-production` environment reviewer accepts that exact
technical record, create a separate short-lived authorization before any
hosting adapter mutates traffic:

```bash
python3 tools/web_release_authorization.py create \
  --technical-promotion /path/to/evidence/web-technical-promotion.json \
  --release-root /srv/chat-room-web/releases/<candidate-id> \
  --release-observation /path/to/evidence/candidate-static.json \
  --route-observation /path/to/evidence/candidate-routes.json \
  --rollback-release-root /srv/chat-room-web/releases/<previous-id> \
  --rollback-observation /path/to/evidence/previous-static.json \
  --output /path/to/evidence/web-production-authorization.json

python3 tools/web_release_authorization.py verify \
  --technical-promotion /path/to/evidence/web-technical-promotion.json \
  --release-root /srv/chat-room-web/releases/<candidate-id> \
  --release-observation /path/to/evidence/candidate-static.json \
  --route-observation /path/to/evidence/candidate-routes.json \
  --rollback-release-root /srv/chat-room-web/releases/<previous-id> \
  --rollback-observation /path/to/evidence/previous-static.json \
  --output /path/to/evidence/web-production-authorization.json
```

The schema-2 write-once authorization binds preview and production origins, candidate and
rollback IDs, source identity, and exact technical-record SHA-256. Its lifetime
is 60–900 seconds, and the technical promotion itself must be no more than 15
minutes old. It contains no cloud token, DNS/CDN credential, provider command,
or broad deployment permission and remains labeled `approved-not-executed`.
Run `python3 Tests/web_release_authorization_test.py` for the mutation suite.

For the filesystem-pointer hosting topology,
`.github/workflows/m4-web-production-release.yml` composes the full boundary.
Configure `CHATROOM_WEB_STORE_ROOT`, `CHATROOM_WEB_PREVIEW_ORIGIN`,
`CHATROOM_WEB_PRODUCTION_ORIGIN`, and `CHATROOM_WEB_SOCKET_PATH` on the dedicated
runner. Dispatch supplies only an exact CI run/artifact. Technical readiness
mutates preview only; the production job waits for `web-production` approval
and refreshes observations after approval. Failed post-switch observation runs
the pre-authorized rollback and strict completion. It has no build/provider
credential and does not support first-release bootstrap. See ADR-0207.

The provider-neutral filesystem adapter may then consume that authorization
exactly once:

Before approving preview health or closing production health, collect at least
three fresh static/route observation pairs over at least 60 seconds and bind
them with `tools/web_release_health_window.py`. The tool rejects reused,
out-of-order, split-origin, mixed-release, overly sparse, and stale samples and
writes one immutable `preview` or `production` result. This is sustained
technical health, not percentage rollout or user-experience telemetry. See
ADR-0211.

The production workflow now applies that rule before approval, repeats it on
preview after approval, and applies it again after the production pointer
switch. A failed production window enters the existing pre-authorized rollback
path. These are staged technical gates, not a percentage traffic rollout, and
no real run is claimed. See ADR-0212.

Before the workflow reports success, `tools/web_staged_release_completion.py`
independently reverifies and hashes the reviewed preview window, atomic pointer
execution, production window, and promotion completion into one write-once
record. This prevents evidence from separate attempts being combined during a
later audit. See ADR-0213.

```bash
python3 tools/web_release_execution.py execute \
  --authorization /path/to/evidence/web-production-authorization.json \
  --technical-promotion /path/to/evidence/web-technical-promotion.json \
  --release-root /srv/chat-room-web/releases/<candidate-id> \
  --release-observation /path/to/evidence/candidate-static.json \
  --route-observation /path/to/evidence/candidate-routes.json \
  --rollback-release-root /srv/chat-room-web/releases/<previous-id> \
  --rollback-observation /path/to/evidence/previous-static.json \
  --store-root /srv/chat-room-web \
  --output /path/to/evidence/web-pointer-execution.json
```

It requires the current pointer to equal the authorized rollback release,
writes an exclusive consumption marker before mutation, atomically activates
the already staged candidate, and restores the rollback pointer if status or
evidence commit fails. Its result is deliberately
`pointer-switched-awaiting-external-observation`; run the HTTPS/static and
application-route probes again before calling the public release healthy. The
pure evidence verifier omits `--store-root` and uses `verify` with the same
remaining inputs. Run `python3 Tests/web_release_execution_test.py` for replay,
wrong-current-state, rollback-on-failure, and mutation coverage.

After the pointer switch, create fresh external static and application-route
observations, then bind them to the execution record:

```bash
python3 tools/web_release_completion.py record \
  --execution /path/to/evidence/web-pointer-execution.json \
  --authorization /path/to/evidence/web-production-authorization.json \
  --technical-promotion /path/to/evidence/web-technical-promotion.json \
  --release-root /srv/chat-room-web/releases/<candidate-id> \
  --pre-release-observation /path/to/evidence/candidate-static-before.json \
  --pre-route-observation /path/to/evidence/candidate-routes-before.json \
  --rollback-release-root /srv/chat-room-web/releases/<previous-id> \
  --rollback-observation /path/to/evidence/previous-static.json \
  --post-release-observation /path/to/evidence/candidate-static-after.json \
  --post-route-observation /path/to/evidence/candidate-routes-after.json \
  --output /path/to/evidence/web-production-completion.json
```

Both post-switch observations must match the exact production origin and
candidate, occur no earlier than the pointer execution, complete within 60–900
seconds (default 600), and remain within five minutes of one another. The
write-once `production-promotion-observed` result binds SHA-256 of execution and
both post-switch observations. Reverify it later with `verify` and the same
inputs. `Tests/web_release_completion_test.py` rejects pre-switch reuse, late or
split observations, identity mutation, duplicate fields, and changed evidence.

If the candidate is unhealthy after pointer execution, restore only the
rollback target already bound by that execution:

```bash
python3 tools/web_release_rollback_execution.py execute \
  --execution /path/to/evidence/web-pointer-execution.json \
  --authorization /path/to/evidence/web-production-authorization.json \
  --technical-promotion /path/to/evidence/web-technical-promotion.json \
  --release-root /srv/chat-room-web/releases/<candidate-id> \
  --pre-release-observation /path/to/evidence/candidate-static-before.json \
  --pre-route-observation /path/to/evidence/candidate-routes-before.json \
  --rollback-release-root /srv/chat-room-web/releases/<previous-id> \
  --rollback-observation /path/to/evidence/previous-static.json \
  --store-root /srv/chat-room-web \
  --output /path/to/evidence/web-rollback-pointer-execution.json
```

Rollback does not wait for the expired promotion authorization: durable
execution evidence already pre-authorized the exact B→A pair. It requires B to
be current, writes a one-time marker, and restores A atomically. If rollback
evidence persistence fails, A remains active instead of switching back to the
failed B. Its status remains `awaiting-external-observation`; probe restored A
and its application routes, then complete
`web_release_rollback_completion.py`. The completion binds exact A static
bytes/headers, `/api/health`, `/ws`, the rollback execution, origin, and a
ten-minute default recovery window. Generic `web_rollback_evidence.py` remains
an A/B/A no-rebuild rehearsal but cannot close a production incident alone. Run
`python3 Tests/web_release_rollback_execution_test.py` for this failure policy.
Run `python3 Tests/web_release_rollback_completion_test.py` for the observed
recovery boundary. See ADR-0204.

Production Web builds resolve V1 WebSocket traffic to `wss://<page-authority>/ws`
and file traffic below same-origin `/api/`; the HTTPS reverse proxy must own both
routes. A different same-origin WebSocket path can be selected at build time,
but paths that contain another authority, query, fragment, backslash, or dot
segment fail closed:

```bash
cd WebClient
VITE_CHAT_V1_WS_PATH=/chat/ws npm run build
```

Pages served from loopback retain direct port 9528 for local Qt server
development and use the Vite `/api/` proxy. Non-loopback HTTP pages are blocked,
and browser storage cannot override a production server. See ADR-0111.

The supported Web build remains on V1 by default. The M3 V2 engineering preview
is a separate, default-off build configuration:

```bash
cd WebClient
VITE_CHAT_V2_PREVIEW=true \
VITE_CHAT_V2_MESSAGE_FORWARDING=false \
VITE_CHAT_V2_WSS_URL=wss://preview-chat.example.com/v2/web \
VITE_CHAT_APP_VERSION=2.0.0-preview.1 \
npm run build
```

These values are public build metadata, never secrets. The enabled build emits
V2 as a lazy chunk and still sends no V2 traffic until a preview UI explicitly
starts it. See [`WEB_V2_PREVIEW.md`](deployment/WEB_V2_PREVIEW.md) for gateway
alignment, verification, and rollback.

## Incremental CMake server path

The root `CMakeLists.txt` represents the V1 persistence/server-core libraries,
shared V1 Common, non-UI Windows client local-data and transport libraries,
portable Windows update boundaries, and thin `ChatServerHeadless`. On Windows
it also owns the canonical `ChatClient` and update-launcher product graph while
qmake remains a parity fallback. The Windows-only graph compiles the detached
V2 session/device WSS adapter into a static library using checksum-pinned
Protobuf 35.1 and Abseil 20250512.1 source archives; it adds no protocol runtime
DLL to the installer (ADR-0323).

Windows V2 product networking remains default-off. A reviewed preview build
must supply both values to the canonical Windows CMake invocation:

```powershell
cmake -S . -B build/windows-v2-preview -A x64 `
  -DCHATROOM_BUILD_HEADLESS_SERVER=ON `
  -DCHATROOM_BUILD_WINDOWS_CLIENT=ON `
  -DCHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON `
  -DCHATROOM_WINDOWS_V2_WSS_URL=wss://preview-chat.example.com/v2/windows `
  -DSODIUM_ROOT="$env:SODIUM_ROOT"
```

The URL is public build metadata, not a secret. It must be the exact Windows V2
WSS route and cannot contain credentials, query parameters, or a fragment.
Supplying the URL while the switch is off or enabling an incomplete/unsafe
configuration fails CMake generation. Ordinary CI intentionally leaves it off;
activation and rollback require an explicit reviewed preview/cutover build
(ADR-0324).

On a macOS Homebrew development host:

```bash
SODIUM_ROOT="$(brew --prefix libsodium)" \
python3 tools/verify_m0.py --cmake-headless
```

On Ubuntu, the installed `libsodium-dev` search paths need no override. The
command performs inventory validation, Release configuration/build, runs the
clean/restart/query-plan SQLite, password migration, message model, client local
repository, optimistic send, synchronization, attachment outbox, and history
adapter plus raw HTTP upload/download and reconnect CTests; it then starts the resulting process and verifies the exact V1
HTTP health contract. CMake never installs or downloads a
dependency; use `SODIUM_ROOT` or normal CMake search paths. Continue using qmake
as a Windows parity/fallback build during the current migration window; ADR-0159
promotes the native-equivalent CMake payload as canonical packaging input.

The same CTest gate generates a one-day localhost certificate/key in a temporary
directory and runs a TLS trust-policy negative/positive pair. The untrusted
certificate must fail before the application `connected` signal; after the test
process explicitly adds the same hostname-valid certificate as a CA, the signal
must occur only after `QSslSocket::encrypted`. The key is deleted with the
temporary directory and is never a product or committed credential.

The `m4_update_*` portion of the same gate runs canonical Ed25519 verification,
semantic decision policy, atomic replay-state repository, and complete manifest
application-order tests. These targets perform no download, Authenticode check,
installer launch, UI action, or product-key configuration.

Additional M4 CTests cover sequential bounded manifest/signature fetch, bounded
private Setup staging and cleanup, portable size/SHA-256, and platform trust
classification. A macOS or Ubuntu `UnsupportedPlatform` result is expected for
Authenticode/launch and is not positive Windows evidence; native signed Windows
verification remains mandatory.

The orchestration CTests then compose these layers in their required trust
order. They require manifest verification, policy, and replay acceptance before
any Setup request; require installer trust before exposing a typed prepared
handoff; and cover zero rollout, invalid signature, trust rejection, parallel
refusal, cancellation, destruction, and temporary-file cleanup. Test-only trust
injection on non-Windows exercises sequencing and must not be cited as positive
Authenticode evidence.

The next six M4 CTests cover the post-preparation lifecycle: closed helper
arguments and launcher results, owner-only atomic pending state, UUID-bound
one-time result consumption, running-version startup reconciliation, helper
runtime staging, and the ready/persist/commit barrier. The coordinator may
authorize normal quit only after pending state is durable. macOS/Ubuntu use an
injected handshake to verify the protocol and failure cleanup; native Windows
events, helper processes, signatures, and Setup execution still require the
Windows product gate.

CI runs inventory and web verification on every push and pull request through
`.github/workflows/m0-baseline.yml`.

Windows product verification and non-product Qt portability builds run through
`.github/workflows/m0-product-builds.yml`. The supported client scope and the
distinction between product and development hosts are documented in
`docs/architecture/SUPPORT_MATRIX.md`.

The native Windows job also configures the root CMake project with
`CHATROOM_BUILD_WINDOWS_CLIENT=ON` and `BUILD_TESTING=OFF`, then builds the
`ChatClient` and `ChatRoomUpdateLauncher` targets using MSVC. Both PE files must
carry the canonical `VERSION`. The source-graph policy is available on any host:

```bash
python3 Tests/windows_cmake_product_target_test.py
```

`CHATROOM_BUILD_WINDOWS_CLIENT=ON` fails configuration on non-Windows hosts.
ADR-0155 initially made the CMake executables verification-only and kept qmake
as packaging input. ADRs 0156-0158 supplied deployed-runtime, helper, install,
upgrade, downgrade, and uninstall equivalence; ADR-0159 now uses CMake as the
canonical unsigned payload/NSIS input while retaining qmake deployment as the
comparison and rollback path.

As the first parity stage, Windows CI runs `windeployqt` on the CMake client in
an isolated directory and invokes:

```bash
python3 tools/compare_windows_client_payloads.py \
  --baseline /absolute/qmake/payload \
  --candidate /absolute/cmake/payload \
  --version 1.2.3 \
  --source-revision 0123456789abcdef0123456789abcdef01234567 \
  --output /absolute/evidence/cmake-payload-parity.json
```

The inventories must be exactly equal and all files except `ChatClient.exe` and
`ChatRoomUpdateLauncher.exe` must have identical size/SHA-256. Those two PE
files may differ because the build systems generate different binaries; each is
still version-checked separately. The comparator rejects symlinks, empty files,
case-insensitive path collisions, debug/server artifacts, missing SQLite or
libsodium, and any runtime drift without writing evidence. Its closed evidence
is validated and hashed by `windows_artifact_manifest.py`. Run the portable
negative policy with `python3 Tests/windows_client_payload_parity_test.py`.

After payload parity and before the canonical installer gate, native CI
uses `tools/verify_windows_cmake_installer.ps1` to compile an isolated temporary
NSIS from the CMake directory. It requires an explicitly unsigned Setup, clean
silent install, canonical client/helper PE versions, SQLite/libsodium presence,
a live client process, the helper ready/commit handshake and UUID-bound
`trust-rejected` evidence for an unsigned Setup, deletion of that rejected
fixture, clean uninstall, and preserved account-local data. The script always
attempts cleanup on failure. Its cross-host source policy is:

```bash
python3 Tests/windows_cmake_installer_gate_test.py
```

The initial ADR-0157 slice deliberately stopped before upgrade/downgrade parity
and did not replace the uploaded qmake installer. No signing success is inferred
from unsigned rejection.

ADR-0158 extends the same temporary CMake gate before the packaging switch. It
compiles and installs a synthetic `0.9.0` predecessor, adds a stale program
sentinel, upgrades to canonical `VERSION`, and requires atomic program-directory
replacement, no stage/backup residue, traceable HKCU registration, and preserved
account data. It then requires exit code 4 and no mutation while the CMake client
is running, rejects the predecessor downgrade without changing the current
registration/files/data, and finally completes the helper and uninstall checks.
The predecessor reuses current payload bytes and validates installer mechanics;
real historical binary/schema compatibility is still a separate release gate.

ADR-0159 promotes `build/m4/windows-cmake-payload` into
`build/m0/artifacts/windows/client` only after all preceding native gates pass.
The qmake deployment remains isolated at `build/m4/windows-qmake-payload`.
Before NSIS compilation, the promotion copy is hashed again against its CMake
source with `--require-executable-byte-equality`, including both PE files.
`windows_artifact_manifest.py` schema 4 records `buildSystem: cmake`,
binds `cmake-payload-parity.json`, and requires the evidence's CMake candidate
inventory to equal every final canonical payload size/SHA-256. A failure creates
no upload; rollback changes the one promotion source back to qmake.

Before any protected signing workflow accepts that uploaded artifact, run:

```bash
python3 tools/verify_windows_unsigned_artifact.py \
  --artifact-root /absolute/downloaded/windows-artifact \
  --version-file VERSION \
  --source-revision 0123456789abcdef0123456789abcdef01234567 \
  --qt-version 6.11.1
```

The verifier independently requires schema 4, `buildSystem: cmake`, exact
version/revision/toolchain identity, sorted closed payload declarations, required
client/helper/Qt Core/platform/SQLite/libsodium files, the exact unsigned Setup
name, parity-evidence metadata, checksum closure, exact bytes, no links, and no
extra files. It does not interpret Authenticode on non-Windows; the protected
Windows runner must additionally require all three signing subjects to be
unsigned before applying its machine-store certificate. Run its mutation suite
with `python3 Tests/windows_unsigned_artifact_verifier_test.py`.

A protected signing invocation must also create and immediately verify a closed
intent with `tools/windows_protected_release_intent.py`. The intent records the
approved canonical version/revision, exact product-trust artifact run and
derived channel-specific artifact name, stable/beta channel, machine-store certificate SHA-1 selector,
expected certificate SHA-256 identity, credential-free HTTPS RFC 3161 URL,
`windows-production-signing` environment, `self-hosted-windows-signing` runner
class, and fresh UTC time. It accepts no certificate bytes, private key,
password, or token. Intents older than two hours or more than five minutes in
the future fail. Run its mutation suite with:

```bash
python3 Tests/windows_protected_release_intent_test.py
```

An intent means “approved for protected signing, not published”; candidate
assembly must hash and retain it, and publication requires a later independent
authorization boundary.

`windows_release_candidate.py assemble` requires
`--protected-signing-intent`. Candidate schema 6 stores the exact intent bytes at
`evidence/protected-signing-intent.json`, declares its path explicitly, hashes it
in the candidate file list and `SHA256SUMS`, then reruns semantic intent
verification during both assembly and independent candidate verification. The
candidate mutation suite proves that rewriting intent content and recomputing
the surrounding manifest/checksum still fails semantic validation.

The NSIS policy has two explicit output identities. Ordinary builds omit
`RELEASE_BUILD` and produce
`ChatRoom-<version>-unsigned-verification-Setup.exe`. A protected signing build
passes `/DRELEASE_BUILD=1` and produces exactly
`ChatRoom-<version>-Setup.exe`. Release mode also has an explicit two-pass
uninstaller boundary. `/DEXPORT_UNINSTALLER=1` lets NSIS generate the real
uninstaller and invokes only `export_windows_uninstaller.py` to copy its exact
bytes to `ChatRoom-<version>-Uninstall.exe`; no signing command or credential is
passed to NSIS. After the protected workflow signs that standalone file,
`/DIMPORT_SIGNED_UNINSTALLER=1` embeds it as the installed `Uninstall.exe` while
omitting a second generated uninstaller. Ordinary unsigned CI still uses
`WriteUninstaller` directly and is unchanged. Validate the closed export and
mode policies with:

```bash
python3 Tests/windows_release_installer_mode_test.py
python3 Tests/windows_uninstaller_export_test.py
```

The protected candidate workflow consumes this two-pass boundary and closes the
standalone signed uninstaller as the fourth Authenticode subject. Positive
execution still depends on the dedicated protected Windows runner.

## Protected Windows Signing Candidate

`.github/workflows/m4-windows-protected-signing.yml` is a manual,
candidate-only workflow. Configure the `windows-production-signing` environment
with required reviewers and no administrator bypass. Its runner must carry the
labels `self-hosted`, `windows`, `x64`, and `self-hosted-windows-signing`, and
should be ephemeral or reset to a known-clean state after every job.

Provision the runner outside this repository with Python, Git, PowerShell, NSIS
3.12 at its standard Program Files location, Windows SDK `signtool.exe` on
`PATH`, and exactly one approved, currently valid code-signing certificate in
`Cert:\LocalMachine\My`. Its private key should be non-exportable and accessible
only to the runner service identity. The workflow accepts no PFX, certificate
password, private key, cloud signing credential, or dependency installer.

The reviewer supplies the exact protected commit, product-trust-build run ID,
stable/beta channel, public SHA-1 certificate selector, expected public SHA-256
certificate identity, and credential-free HTTPS RFC 3161 URL. Dispatch strings
enter PowerShell only through environment variables; direct interpolation of
workflow inputs into shell blocks is forbidden. The workflow then:

1. checks out and validates the exact reviewed revision;
2. creates and verifies the two-hour-bounded protected-signing intent;
3. downloads the exact channel-specific unsigned product-trust artifact;
4. requires its closed schema-4 trust and confirms all three subjects are unsigned;
5. signs the client and update helper from the machine certificate store;
6. re-runs the signed client diagnostic, requires equality with the unsigned
   diagnostic, and binds fresh trust evidence to the signed PE;
7. exports and signs the generated uninstaller, imports it into release-mode
   Setup, then signs Setup;
8. generates and independently verifies four-subject signature evidence;
9. installs Setup into a clean dedicated path, requires the installed
   client/helper/uninstaller bytes and signatures to match, verifies registration,
   uninstalls, and independently verifies the closed acceptance evidence;
10. assembles and independently verifies a schema-6 candidate closing signed-PE
    product trust; and
11. uploads one seven-day `signed-not-published` evidence artifact.

It cannot create a GitHub Release, sign or publish an update manifest, contact a
release endpoint, or promote a channel. Check this static boundary with:

```bash
python3 Tests/windows_protected_signing_workflow_test.py
```

That policy test and YAML parsing do not prove a valid native signature. Positive
signing remains unverified until the protected environment and runner execute a
real approved candidate successfully.

The unsigned Windows NSIS gate now compiles a synthetic predecessor outside the
uploaded artifact, installs it, and upgrades to canonical `VERSION`. It checks
whole-program-directory replacement, marker ownership, rollback scaffolding,
stale-file removal, registry traceability, AppData preservation, and cleanup of
staging/backup directories. The predecessor uses the current payload and proves
installer mechanics only; real previous-binary/schema compatibility, running-app
handling, signing, and Windows 10/11 clean-host behavior remain M4 work. CI also
re-runs the older Setup after upgrade and requires a nonzero downgrade result
without changing the current program registration, executable, or AppData.

The default-off Windows update-manifest authoring boundary is tested separately:

```bash
python3 Tests/windows_update_manifest_test.py
```

It generates a temporary Ed25519 key, signs/verifies canonical release metadata,
and rejects tampering/expiry/unsafe URLs. It does not create or trust a product
key and refuses the unsigned verification Setup name. See
[`UPDATE_MANIFEST.md`](../packaging/windows/UPDATE_MANIFEST.md) and ADR-0117.

Production update-manifest signing uses the separate static policy boundary:

```bash
python3 Tests/windows_update_protected_signer_policy_test.py
```

Provision a dedicated protected runner with OpenSSL 3 plus its reviewed PKCS#11
provider, a hardware-backed non-exportable Ed25519 key, and the matching public
PEM. The runner service authenticates to the HSM out of band. Set only the
credential-free PKCS#11 object URI in `CHATROOM_UPDATE_SIGNING_KEY_URI`; do not
put a PIN, password, key bytes, PEM private-key path, or provider installer in
workflow input, environment secrets, arguments, logs, or artifacts. Invoke
`sign_windows_update_manifest_protected.ps1` with the canonical manifest,
previously absent signature path, reviewed manifest key ID, public PEM path, and
public-key-file SHA-256. It inspects canonical identity, signs through PKCS#11,
immediately verifies through the public PEM, and publishes only the exact
64-byte detached signature. Protected workflow orchestration and positive HSM
execution evidence remain later M4 steps.

The self-contained update-channel candidate policy is tested with:

```bash
python3 Tests/windows_update_channel_candidate_test.py
```

`tools/windows_update_channel_candidate.py assemble` accepts only an already
verified schema-6 Windows candidate, canonical manifest, detached signature,
reviewed public PEM, and public release identity. It closes those exact bytes
in one atomic, immutable `signed-update-channel-not-published-candidate` and
requires manifest Setup hash/size/publisher/version/revision/channel equality.
The manifest `signingKeyId` must select a primary/secondary public PEM retained
by signed candidate schema 6, and the external verification PEM must be byte-
identical; a valid signature under an uncompiled key is rejected. See ADR-0196.
Its recorded UTC assembly instant permits durable later verification without
weakening live freshness at assembly. It contains no private key and performs
no upload or channel mutation. Fixture tests are not positive PKCS#11,
Authenticode, Windows clean-host, or publication evidence. See ADR-0176.

The manual `.github/workflows/m4-windows-protected-update-signing.yml` consumes
the exact candidate artifact from an approved prior Windows protected-signing
run. It executes in the distinct `windows-update-production-signing`
environment on a dedicated `self-hosted-windows-update-signing` runner. The
credential-free PKCS#11 object URI and reviewed public PEM path are runner
configuration, not workflow inputs or repository secrets. Public dispatch
inputs bind revision/run, stable or beta channel, monotonic sequence, key/public
digest, minimum version, deterministic rollout, installer URL, and Authenticode
publisher. The workflow creates a seven-day manifest for the candidate's exact
Setup, signs/verifies it, assembles/verifies ADR-0176, and uploads exactly one
seven-day `signed-update-not-published` artifact. It has no publication or
channel-mutation step. Check the orchestration boundary with:

```bash
python3 Tests/windows_protected_update_workflow_test.py
```

A real approved run is still required before claiming the HSM/provider,
signature, Windows candidate, or update channel works in production. See
ADR-0177.

Before a provider adapter may mutate an existing stable or beta endpoint, use
`tools/windows_update_release_authorization.py create` with the exact closed
candidate and a canonical snapshot of the manifest expected to be current. The
tool revalidates the complete candidate, requires its signature and manifest to
be live, rejects a candidate older than 24 hours, derives the current sequence
and SHA-256 from the snapshot, and requires a strictly advancing target
sequence. It emits a 60–900-second write-once
`update-channel-promotion-approved-not-executed` record for the fixed
`windows-update-production` environment. Schema 2 binds both current and target
rollout percentages. For the same version/source, a percentage change is
rejected and must use the health-bound expansion authorization. Incident
forward fixes use the dedicated post-halt authorization described below. The
general path has no network or mutation logic:

```bash
python3 Tests/windows_update_release_authorization_test.py
```

The current snapshot is a compare-and-swap expectation, not evidence of a live
fetch. A later executor must observe exact endpoint equality immediately before
mutation. Initial channel bootstrap is intentionally not authorized by this
path. See ADR-0178 and ADR-0192.

`tools/windows_update_channel_store.py stage` prepositions a complete verified
candidate under `releases/<update-manifest-sha256>/` through copy/reverify/atomic
rename. Repeating identical staging is idempotent; changed bytes, symlinks, or
unsafe store boundaries fail. This component deliberately has no active pointer
or network adapter, so staging cannot publish:

```bash
python3 Tests/windows_update_channel_store_test.py
```

Provider storage must preserve the same immutable, content-addressed,
pre-stage-before-activate behavior. See ADR-0179.

`tools/windows_update_release_execution.py execute` is the authorization
consumer for a pointer-based provider adapter. It revalidates the target and
currently active complete releases, requires exact expected-current manifest
SHA/sequence, writes a non-replay marker before mutation, atomically switches
`active-channel.json`, and writes
`channel-pointer-switched-awaiting-external-observation` evidence. If final
validation or evidence persistence fails, it restores the previous pointer but
retains consumption, so retry requires a new authorization:

```bash
python3 Tests/windows_update_release_execution_test.py
```

This proves local adapter semantics only. A provider must supply equivalent
conditional pointer mutation, and public HTTPS observations remain required.
See ADR-0180.

After pointer execution, run `tools/windows_update_release_probe.py` from an
independent network location against the fixed production `manifest.json` URL.
It fetches that manifest, adjacent detached signature, and the manifest's exact
Setup URL over trusted TLS without redirects or transformation. All three must
match candidate bytes and the update origin must return HSTS, `nosniff`, exact
length/content type, `no-store` for manifest/signature, and immutable caching
for Setup. Evidence is write-once and contains no provider credential:

```bash
python3 Tests/windows_update_release_probe_test.py
```

The test uses an isolated localhost CA and proves failure semantics only. A real
production-origin observation is required for release completion. See ADR-0181.

`tools/windows_update_release_completion.py record` binds that post-switch
observation to the exact authorization/execution and target/rollback candidates.
Channel, manifest SHA, sequence, version, and revision must agree; observation
must occur after execution and the complete operation must finish within
60–900 seconds (ten minutes by default). The immutable result is
`production-update-promotion-observed`:

```bash
python3 Tests/windows_update_release_completion_test.py
```

This is point-in-time public delivery evidence, not continuous availability,
global CDN convergence, successful client installation, or rollout health. See
ADR-0182.

For an incident after observed B promotion,
`tools/windows_update_release_rollback.py execute` derives the exact B→A pointer
transition from completion evidence. B must still be active, A must be a
complete retained candidate with a currently valid signed manifest, and
completion consumption is persisted before atomic restoration. If evidence
writing then fails, A stays active; the tool never reactivates failed B:

```bash
python3 Tests/windows_update_release_rollback_test.py
```

This is a rollout halt, not an automatic client downgrade. Devices that already
accepted B's higher manifest sequence reject old A and remain on B until a newly
signed, higher-sequence forward corrective release. Before restoring A, the
filesystem adapter retains the exact promotion-completion-bound incident under
`.rollout-incidents/` and exclusively opens `.open-rollout-incident.json`.
While that marker exists, ordinary promotion and rollout-expansion executors
fail before consuming their authorizations. Missing retention, byte divergence,
links, malformed identity, and future time fail closed. See ADR-0183 and
ADR-0198.

After A is restored, run the HTTPS probe against A and bind its new observation
with `tools/windows_update_rollback_completion.py record`. A manifest SHA,
sequence, version, and revision must match rollback evidence, and observation
must complete within ten minutes by default. The result is
`production-update-rollout-halt-observed`:

```bash
python3 Tests/windows_update_rollback_completion_test.py
```

This proves the restored channel bytes at one instant, not downgrade of clients
already on B or global continuous availability. See ADR-0184.

After that observed halt, authorize the corrective release C with
`tools/windows_update_forward_fix_authorization.py create`. The tool
reconstructs the complete rollback chain and live A/B/C candidates. C must use
a higher numeric version and manifest sequence than B, a different source
revision, exactly 100 percent rollout, and a minimum updatable version that
includes B. C's update key ID and exact public PEM must already be compiled into
B, otherwise stranded B clients could not authenticate the repair. The
60-to-900-second write-once result is
`forward-fix-approved-not-executed`; it contains no credential and performs no
staging, publication, pointer mutation, or network request:

```bash
python3 Tests/windows_update_forward_fix_authorization_test.py
```

Execution still requires one-time incident-bound consumption and strict public
HTTPS observation. An authorization record alone is not recovery evidence. See
ADR-0197.

After pre-staging C by manifest SHA-256, run
`tools/windows_update_forward_fix_execution.py execute`. It reconstructs the
authorization and open incident, requires their B/A identities and original B
promotion completion to match, requires A to be active, and consumes the
authorization before atomically switching to exact C. Evidence-write failure
restores A while leaving both the consumption and open incident in place. A
successful local result is
`forward-fix-pointer-switched-awaiting-external-observation`; the incident also
remains open until a later strict HTTPS completion step:

```bash
python3 Tests/windows_update_forward_fix_execution_test.py
```

See ADR-0199.

Probe C from an independent network location with the existing strict update
probe, then run `tools/windows_update_forward_fix_completion.py complete`.
The observation must match C's canonical manifest, detached signature, Setup,
version, source, sequence, and channel after execution and within ten minutes by
default. The tool writes `production-forward-fix-observed`, retains a resolved
incident record bound to execution/completion SHA-256, and only then removes the
exclusive open marker:

```bash
python3 Tests/windows_update_forward_fix_completion_test.py
```

This is point-in-time endpoint evidence, not global convergence, installation
success on every affected B device, or post-fix rollout health. See ADR-0200.

Before broadening a staged rollout, evaluate a reviewed aggregate observability
export against `packaging/windows/rollout-health-policy.json`:

```bash
python3 tools/windows_update_rollout_health.py record \
  --completion /release/promotion-completion.json \
  --candidate-root /release/windows-update-channel-candidate \
  --metrics /release/aggregate-rollout-metrics.json \
  --policy packaging/windows/rollout-health-policy.json \
  --output /release/rollout-health-decision.json
```

Stable advances through `1/5/25/50/100` after at least two hours and 100 install
outcomes; beta uses `10/25/50/100`, 30 minutes, and 25 outcomes. The exact input
schema contains aggregate counters only and must use canonical JSON. Output cannot authorize or mutate a
channel: incomplete evidence is `hold`; `halt-recommended` uses the existing
halt procedure and still needs a higher-sequence forward fix for updated
clients. See ADR-0191.

`windows_update_rollout_expansion_authorization.py create` turns only an
`expand-eligible` result into a short-lived, still-unexecuted authorization. It
reconstructs the full prior promotion, verifies a detached Ed25519 signature on
the canonical metrics export against a reviewed public PEM digest/key ID, and
revalidates current and target candidates. The target must retain the exact
signed Windows candidate, installer, signing key, minimum version, and rollout
seed while advancing sequence to exactly the policy's next percentage. Use
`--help` for the explicit completion-chain paths; the tool accepts no private
key or provider credential. Protected workflow orchestration and real provider
execution are still separate gates. See ADR-0193.

After immutable staging, `windows_update_rollout_expansion_execution.py execute`
reconstructs that authorization, requires current and target paths to match
their content-addressed store IDs, compares the active digest/sequence and
rollout seed/percentages, then records consumption before atomically switching
the pointer. Evidence failure restores the previous pointer without making the
authorization reusable. Its result still awaits strict public HTTPS
observation; local success is not rollout completion. See ADR-0194.

Probe the expanded target through `windows_update_release_probe.py`, then use
`windows_update_rollout_expansion_completion.py record` to bind that exact
manifest/signature/Setup observation to expansion execution within ten minutes.
The `production-rollout-expansion-observed` record retains both percentages and
the seed. It is point-in-time delivery evidence; every later percentage still
requires a new post-completion metrics window and authorization. See ADR-0195.

Before compiling a product-update-enabled Windows client, create and verify a
short-lived public trust intent with
`tools/windows_update_product_trust_intent.py`. It binds exact source/version,
stable or beta manifest URL, primary key ID/canonical Ed25519 raw public key/PEM
SHA-256, and an optional complete distinct secondary key for rotation. It
contains no private key or provider credential:

```bash
python3 Tests/windows_update_product_trust_intent_test.py
```

Ordinary builds remain disabled. A later protected build workflow must consume
and retain this exact intent before the client can be signed. See ADR-0185.

The final Windows client exposes one side-effect-free release diagnostic:

```powershell
ChatClient.exe --chatroom-print-update-trust-json
```

It exits before UI, single-instance locking, local state, or network work and
reports only compiled public update trust. Ordinary Windows CI requires
`enabled: false` and an empty key ring. A protected product build must instead
match every field to ADR-0185 before packaging/signing. Check the source/build
boundary with `python3 Tests/windows_update_trust_diagnostic_policy_test.py`.
See ADR-0186.

Capture the diagnostic stdout and bind it to the exact unsigned PE and reviewed
intent with `tools/windows_update_product_trust_evidence.py create`. Every
public trust field must match; the record retains SHA-256 of the PE, intent, and
diagnostic plus source/version and capture time. Creation requires a live intent,
while later audit replays that capture instant:

```bash
python3 Tests/windows_update_product_trust_evidence_test.py
```

This is compiled-public-trust evidence, not Authenticode or release evidence.
See ADR-0187.

Unsigned artifact schema 4 always records `productUpdateTrust`. Ordinary CI
sets it to `null`; release-intended artifacts close the intent, diagnostic,
binary evidence, and reviewed public PEM files and rebind them to the exact
client PE. Signing intake uses `--require-product-update-trust` to reject null,
old-schema, missing, or changed trust. See ADR-0188.

The manual `m4-windows-product-trust-build.yml` workflow creates that
release-intended artifact on the dedicated
`self-hosted-windows-update-trust-build` runner in the protected
`windows-update-product-trust` environment. It first verifies the exact
ordinary schema-4 artifact with `--forbid-product-update-trust`, then rebuilds
only `ChatClient.exe` from the reviewed public intent. Runtime DLLs and the
update helper must be byte-identical to the ordinary CMake/default-off payload.
The final PE and installed unsigned-Setup PE must report identical trust before
the workflow uploads one seven-day `unsigned-product-trust` artifact. The
workflow has no signing or publication authority. Run its source-policy test
with `python3 Tests/windows_product_trust_build_workflow_test.py`. No successful
native protected run is recorded in this repository yet; see ADR-0189.

The provider-neutral post-signing acceptance policy is checked with:

```bash
python3 Tests/windows_release_signature_policy_test.py
```

On Windows, `tools/verify_windows_release_signatures.ps1` accepts only the final
canonical client, update helper, standalone `ChatRoom-<version>-Uninstall.exe`,
and `ChatRoom-<version>-Setup.exe`. Each must
have valid Authenticode, the reviewed SHA-256 publisher certificate, and a
timestamp certificate before immutable evidence is created. The script accepts
no private key or password input. Native unsigned CI renames its Setup only for
a negative check and requires rejection with no evidence; it does not exercise
the positive signed path.

After a positive Windows probe, independently bind that evidence to the final
candidate bytes before any upload or update-manifest signing:

```bash
python3 tools/windows_release_evidence.py \
  --evidence /release/windows-release-signatures.json \
  --client /release/ChatClient.exe \
  --launcher /release/ChatRoomUpdateLauncher.exe \
  --uninstaller /release/ChatRoom-1.2.3-Uninstall.exe \
  --installer /release/ChatRoom-1.2.3-Setup.exe \
  --version-file VERSION \
  --source-revision <40-lowercase-git-sha> \
  --expected-signer-sha256 <64-lowercase-certificate-sha256>
```

`python3 Tests/windows_release_evidence_test.py` verifies closed-schema,
freshness, identity, symlink, final-byte, publisher, timestamp, and role-order
rejection. Test fixtures do not represent actual Authenticode evidence.

Assemble the verified complete payload into a previously absent candidate
directory, then verify that directory independently before transfer:

```bash
python3 tools/windows_release_candidate.py assemble \
  --payload-root /release/signed-client \
  --uninstaller /release/ChatRoom-1.2.3-Uninstall.exe \
  --installer /release/ChatRoom-1.2.3-Setup.exe \
  --signature-evidence /release/windows-release-signatures.json \
  --protected-signing-intent /release/protected-signing-intent.json \
  --install-acceptance-evidence /release/windows-install-acceptance.json \
  --product-trust-intent /release/product-update-trust-intent.json \
  --product-trust-diagnostic /release/signed-product-update-trust-diagnostic.json \
  --product-trust-evidence /release/signed-product-update-trust-evidence.json \
  --product-trust-primary-public-key /release/product-update-primary-public.pem \
  --output-root /release/candidates/windows-stable-1.2.3 \
  --version-file VERSION \
  --source-revision <40-lowercase-git-sha> \
  --channel stable \
  --qt-version 6.11.1 \
  --expected-signer-sha256 <64-lowercase-certificate-sha256>

python3 tools/windows_release_candidate.py verify \
  --candidate-root /release/candidates/windows-stable-1.2.3 \
  --version-file VERSION \
  --source-revision <40-lowercase-git-sha> \
  --channel stable \
  --qt-version 6.11.1 \
  --expected-signer-sha256 <64-lowercase-certificate-sha256>
```

The assembler requires Qt Core/platform, SQLite, and libsodium runtimes; rejects
server/debug/key/environment files and links; rechecks evidence after copying;
and atomically exposes the destination only after full validation.
`python3 Tests/windows_release_candidate_test.py` covers its negative paths.
The native acceptance producer is additionally guarded by
`Tests/windows_signed_install_policy_test.py`; its independent closed-schema and
final-byte verifier is covered by `Tests/windows_install_evidence_test.py`.
These local tests are not substitutes for executing the signed installer on the
protected Windows runner.

Candidate verification uses immutable `assembledAt` as the freshness reference
for the retained two-hour intent and 24-hour native observations. Assembly still
requires those inputs to be fresh at the real current time, but an unchanged
archived candidate remains independently verifiable after those operational
windows expire. A candidate whose assembly time is in the verifier's future is
rejected. This prevents freshness policy from becoming accidental artifact
expiry and is covered by `Tests/windows_release_candidate_test.py`.

The Qt gate also compiles `UpdateManifestSignatureVerifierTest`. It generates an
ephemeral Ed25519 keypair and proves canonical verification plus empty-key,
unknown-key, tamper, and non-canonical rejection. The client now links libsodium;
Windows CI copies the vcpkg runtime DLL into the payload and checks it after
install. Ordinary builds have no trusted update key and instantiate no update
network path; only the explicit compiled release configuration below enables it.

`UpdateManifestDecisionPolicyTest`, also included by `--qt`, checks the inactive
post-verification policy: exact signed object/schema/architecture, UTC validity,
per-channel replay state, version routing, deterministic staged rollout, and
bounded installer metadata. It does not persist state, download, authenticate,
or launch an installer.

`UpdateInstallerTrustVerifierTest` accepts only exact local file size/SHA-256.
On non-Windows development hosts it then reports Authenticode unsupported; on
native Windows CI it also compiles WinTrust/crypt32 integration and requires the
deliberately unsigned fixture to be rejected. Acceptance of the future real,
signed and RFC 3161-timestamped Setup remains a separate release test.
The same test requires the locked silent-launch entry point to reject that
unsigned fixture before process creation (or report unsupported off Windows).

`UpdateLauncherCommandTest` checks the external helper's exact one-shot command
contract, UUID-bound result/event names, absolute regular executables, 2 GiB
size bound, lowercase hashes, duplicates, and unknown options. The Qt gate also
builds `ChatRoomUpdateLauncher`; native Windows packaging includes it and is
configured to prove parent handshake/wait, unsigned Setup rejection, atomic
result evidence, and rejected-file cleanup without invoking an installer.

`WindowsUpdateHandoffApplicationServiceTest` injects the platform handshake but
uses real private-directory staging. It checks background execution, exact
parent/UUID/hash arguments through the helper parser, helper plus Qt Core copies,
parallel refusal, `readyToQuit` only after handshake, and rejection before
launch when the installed runtime is incomplete.

`UpdateLauncherResultTest` fixes the client interpretation of the helper's
schema-1 result. It accepts coherent install success/failure and rejects UUID
mismatch, unknown fields/outcomes, contradictory exit codes, unsafe error text,
and timestamps outside the pending-request window.

`UpdateLifecycleRepositoryTest` uses real private directories to prove atomic
single-pending creation, result-not-yet-ready behavior, UUID-derived one-time
consumption, run cleanup, replay prevention, and retention of invalid evidence.

`WindowsUpdateInstallCoordinatorTest` proves the combined fail-closed boundary:
parallel refusal, helper handshake, UUID continuity into durable pending state,
quit authorization only after persistence, and continued execution when an
existing pending lifecycle blocks persistence.
The handoff is two-phase: its parser and service tests require the UUID commit
event, invoke durable authorization only after ready, and deny quit when commit
authorization fails. `UpdateLauncherResultTest` accepts the resulting bounded
`handoff-aborted` evidence without treating it as an install attempt.

`WindowsUpdateStartupServiceTest` exercises the active post-restart policy with
real private directories: empty state, recent update blocking startup, stale
pending recovery, matching installed version, version mismatch, and nonzero
installer failure. The Windows entry point owns only presentation and never
turns raw helper diagnostics into user-facing text.

`WindowsUpdateProductConfigurationTest` proves ordinary builds stay default-off
and validates channel/URL/key policy. Its enabled companion compiles a non-
production fixture through the same preprocessor boundary. The canonical CMake
release build can provide reviewed public configuration as follows (values shown
are placeholders, and no private key is accepted):

```powershell
cmake -S . -B build/release/windows -A x64 `
  -DCHATROOM_BUILD_HEADLESS_SERVER=ON `
  -DCHATROOM_BUILD_WINDOWS_CLIENT=ON `
  -DCHATROOM_ENABLE_WINDOWS_UPDATES=ON `
  -DCHATROOM_UPDATE_CHANNEL=stable `
  -DCHATROOM_UPDATE_MANIFEST_URL=https://updates.example.invalid/windows/stable/manifest.json `
  -DCHATROOM_UPDATE_PRIMARY_KEY_ID=windows-update-YYYY-NN `
  -DCHATROOM_UPDATE_PRIMARY_PUBLIC_KEY_HEX=<64-lowercase-hex-characters> `
  -DSODIUM_ROOT=<absolute-vcpkg-x64-windows-prefix>
cmake --build build/release/windows --config Release `
  --target ChatClient ChatRoomUpdateLauncher
```

For rotation, provide both `CHAT_UPDATE_SECONDARY_KEY_ID` and
`CHAT_UPDATE_SECONDARY_PUBLIC_KEY_HEX`. CMake rejects residual configuration
while disabled, partial enabled/secondary configuration, unsafe URL literals,
and malformed public identifiers/keys before compilation. Run the portable
configuration cases with
`python3 Tests/windows_cmake_update_configuration_test.py`. Public keys and URLs
are reviewable release inputs; private Ed25519 and Authenticode keys must never
be command-line values.

ADR-0138 connects this compiled configuration to `WindowsUpdateController`.
Enabled builds perform one automatic check after the first login and expose a
manual Help action. Preparation is cancellable; only a downloaded and
Authenticode-verified Setup reaches a default-No install prompt. Decline or
handoff failure removes the prepared file. Successful two-phase handoff requests
the chat window's normal draft-flush/disconnect quit path. This composition is
compiled on macOS as portability evidence, but consent, disconnect, successful
signed install, and restart require native Windows release verification.

`UpdateStateRepositoryTest` checks creation/reload of an owner-only UUIDv4,
atomic per-channel sequence/digest persistence, idempotence, replay/conflict
rejection, and corrupt-state failure. Enabled Windows builds derive its `state`
directory separately from lifecycle/results/runs/staging under AppLocalData.

`UpdateManifestApplicationServiceTest` proves the mandatory signature-to-state-
to-policy-to-atomic-acceptance order with an ephemeral key. Tampered or empty
trust creates no state, retries are idempotent, and a signed replay is rejected.
Ordinary builds do not invoke it; ADR-0138 invokes it only through validated
compiled product trust and owner-local state.

`UpdateInstallerDownloadTransportTest` injects deterministic responses while
retaining the production HTTPS-only request policy. It checks exact success,
manual redirect refusal, 2 GiB/schema alignment, Content-Length and streaming
bounds, cancellation, and partial-file cleanup. It does not claim a public TLS
origin or end-to-end Windows update.

`UpdatePreparationApplicationServiceTest` exercises the default-off signed-
manifest-to-download-to-trust composition with an ephemeral key and deterministic
transport. It proves deferred rollout makes no request, concurrent work is
rejected, trust runs off the application thread, and trust failure removes the
file. Native real-Setup acceptance and launch are still not claimed.
Its `Ready` assertion also requires a complete path/size/SHA-256/publisher
thumbprint value and an atomically activated random `.exe`; rejected results
expose an empty value.

`UpdateManifestFetchTransportTest` exercises the separate default-off discovery
transport with deterministic HTTPS responses. It checks exact sequential
`manifest.json`/`manifest.json.sig` requests, same-origin path binding, manual
redirect refusal, 64 KiB/64-byte response bounds, timeout headers, cancellation,
and failure-byte suppression. It does not configure a product origin or weaken
normal Qt TLS validation.

`UpdateCheckApplicationServiceTest` drives the complete pre-launch service with
ephemeral trust. It proves the exact manifest/signature/installer
request order, successful verified-file handoff, signature rejection before an
installer request, staged-rollout deferral without an installer request, and
parallel-check refusal. It does not prove production keys, public TLS,
Authenticode, product composition, or native update UX.
It also verifies that the complete signed installer evidence survives the
discovery-to-ready composition unchanged.

`WindowsClientInstanceGuardTest` checks the shared liveness-mutex contract. On
Windows it requires first acquisition, duplicate refusal, and release/reacquire;
non-Windows development hosts report the platform boundary. Installer policy
tests and pinned local NSIS compilation require `.onInit` to reject that mutex
before staging, while native CI is configured for the installed-process gate.

## Java V2 Backend

The additive M3 workspace requires JDK 21. It carries its own checksum-pinned
Gradle Wrapper, so a system Gradle installation is not required:

```bash
python3 tools/verify_m0.py --java
```

Equivalently, run `./gradlew --no-daemon check` from `Backend/`. On a macOS
development host with multiple JDKs, select JDK 21 explicitly when needed:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  python3 tools/verify_m0.py --java
```

`.github/workflows/m3-java.yml` runs the same gate on Ubuntu with Temurin 21.
The Java workspace is not yet on the production traffic or data path; the C++
V1 verification remains required during the compatibility window.
The gate includes the inactive `object-storage-s3` module. Its tests use fixture
credentials and the real AWS presigner but perform no network request. Passing
them proves request construction and fail-closed mapping, not compatibility
with an Amazon S3, Tencent COS, MinIO, or other real bucket.
It also includes the detached `profile-image-codec` adapter. Its tests use only
in-memory images and prove PNG-only bounded dimensions/pixels, deterministic
metadata-stripping re-encoding, and malformed/non-PNG/header-bomb rejection.
The module performs no network or database work and is not installed in either
product listener.
Gateway tests also drive the inactive cleanup loop through a deterministic
manual scheduler and verify healthy cadence, capped failure backoff, recovery,
close behavior, and fixed-cardinality `/metrics` output without starting a
thread or contacting object storage.
An operator-only real-provider probe now covers create-only PUT replay, checksum
HEAD, Web CORS, and deletion. It is excluded from `check` and gateway startup,
requires exact side-effect confirmation, generates only a random test-prefix
object, and verifies cleanup on success or failure. Follow
`docs/deployment/ATTACHMENT_OBJECT_STORAGE_ACCEPTANCE.md`; do not run it against
a production bucket or treat its PASS as the remaining expiry/policy/capacity
evidence.
The Java gate includes embedded-channel tests for the bounded V2 binary
WebSocket frame decoder, single-use ClientHello negotiation, and fresh-login
connection state machine. They verify server-bound identity, secret cleanup,
generic rejection, failure normalization, and session-spoofing denial. These
tests also verify binary envelope egress plus fixed WebSocket 1002/1009 close
mapping for unsafe frames. They do not open a listener or imply that V2 is ready
to receive traffic.
The protocol-binding gate also compiles and round-trips permanent V2 messaging
types 100..113 and content type 1 (bounded nonempty UTF-8 text). Type 104 is the
uncorrelated authenticated live `MessageRecord` event. It verifies the
fixed `SubmitMessage` and `SubmitReplyMessage` golden payloads in Java,
generated TypeScript, and generated C++. Type 105 is a distinct reply command,
so an older server rejects it instead of silently accepting a plain message;
the additive server-authored record reference contains target identity and
sequence but no copied quote body (ADR-0328). Types 106--108 reserve an
explicit-capability message-reaction command, response, and ordered live event.
The same reaction command golden bytes are locked in Java, TypeScript, and C++;
policy tests cover the fixed enum, canonical identities, idempotency operation
ID, and changed/sequence invariant. The default-off Web and Windows V2 previews
now advertise and activate the capability only with their complete local
projection/application/UI compositions (ADR-0339). The gate also locks the V2
conversation-directory composite cursor across all
three generated bindings. The generation task also publishes reviewed
TypeScript into `WebClient/src/protocol/v2/generated` and reviewed Windows C++
into `Client/protocol/v2/generated/chat/v2`. The gate snapshots both committed
trees, regenerates, and fails if either changes. Do not edit generated client
files manually. The C++ golden test compiles the committed Windows tree rather
than an ignored temporary copy. Web V2 protocol tests also encode type 105 with
the stable envelope idempotency key and reject malformed, noncanonical, or
non-preceding server reply references before application state sees them.
Web application/cache tests persist only reply target identity in the isolated
V2 IndexedDB, replay the same target and client ID after sync, merge the
authoritative server reference, and render recalled or absent targets without a
copied quote body. The preview UI exposes keyboard buttons for reply and cancel
while remaining behind the existing default-off gate (ADR-0330).
Web reaction tests additionally verify account-scoped IndexedDB persistence,
stable optimistic replay after reconnect history, ACK/history/live convergence,
bounded fixed-kind aggregates, keyboard-native controls, pressed state, and
explicit failed-operation retry. No reaction operation stores session secrets.
Types 109/112/113 reserve the independently negotiated pinned-message command,
response, and event around the existing 110/111 directory allocation. Java,
TypeScript, and C++ lock the same `SetMessagePin` bytes; Java policy tests also
cover canonical identity, operation bounds, changed-only sequence semantics,
pin history-detail identity, and duplicate/unknown capability rejection. Web
and Windows now advertise `MESSAGE_PINS`: each has completed its isolated
durable projection, bounded operation outbox, reconnect replay, ACK/history/live
convergence, target cleanup, and accessible-control gate (ADR-0340).
Types 114/115/116 define the active message-edit command, response, and ordered
event inside the default-off V2 preview, plus additive message revision
metadata. Java, TypeScript, and C++ lock the same `EditMessage` bytes and Java
validates canonical IDs, bounded UTF-8, operation identity, revision limits,
changed-only sequencing, message metadata, and mixed-history detail identity.
The gateway and completed Web/Windows preview compositions now negotiate the
capability only after their durability, reconnect, conflict, UI, and
accessibility gates pass (ADR-0341).
Capability 4 reserves structured mentions through additive fields on
submission, reply, edit, authoritative message, and ordered edit payloads. The
protocol gate locks a Unicode mention across Java, TypeScript, and C++ and tests
the 20-span/10-target bound, canonical IDs, ordered non-overlap, UTF-8
boundaries, and `@` prefix policy. The authenticated gateway now accepts and
projects mentions only for sessions that explicitly negotiate capability 4.
The Web protocol boundary can opt into the same capability and rejects invalid
or unnegotiated inbound spans. Web V2 cache records also retain bounded mention
metadata for authoritative messages and edit intents, clear it on recall, and
discard a malformed cached set without discarding ordinary text. The composed
Web V2 preview now advertises capability 4 after its persistence, composition,
rendering, and accessibility gate passed. Windows source now advertises the same
capability after its client gates passed, while release remains blocked on its
Windows Release and native Widgets interaction gate (ADR-0342).
Types 117/118 define the capability-gated V2 conversation-participant query
and response. Java payload policy and the Java/TypeScript/C++ compatibility
gate require a canonical conversation ID, optional canonical account cursor,
1..100 bound, ascending unique account IDs, current display-name bounds, roles,
and a cursor equal to the last row (ADR-0343).
Type 119 and capability 5 reserve server-authoritative text forwarding. The
command carries canonical source conversation/message IDs, an expected source
revision, and one target conversation while the envelope client message ID is
the destination idempotency key. Java, TypeScript, and C++ lock the same command
bytes; Java additionally bounds canonical identities and revision. The
additive `MessageRecord.forwarded` flag is presentation-only and exposes no
source identity. All gateway and client capability-5 paths remain off until the
PostgreSQL, projection, cache/outbox, reconnect, accessibility, and endpoint
release gates pass (ADR-0344).
V049 adds the non-null destination marker and a request table containing only a
SHA-256 digest bound to the destination message. The PostgreSQL integration gate
proves source/destination active membership and device authority, current-text
copy, revision conflict, exact retry, changed-request conflict, stripped reply/
mention metadata, and history projection. The V049 storage slice by itself does
not register type 119 or advertise capability 5.
V050 adds an inactive, payload-free `conversation_event_outbox` with the
conversation UUID/sequence partition identity and bounded availability,
claim/retry, publication, and failure state. New V2 messages create one row in
the same transaction as their message/entry/reply/mention data; exact retries
create none. The PostgreSQL gate verifies V001-to-V050 migration, same-database
restart, table constraints/index, concurrent idempotency, and exact outbox row
counts. V051 adds the independent claim fencing token and inactive PostgreSQL
relay port. The same gate proves per-conversation head ordering, bounded leases,
delayed retry, expiry reclamation, attempt increments, stale-token/wrong-owner
rejection, and idempotent publication. No scheduler, Redis connection,
historical backfill, or product route is activated by these expand slices
(ADR-0348, ADR-0349).
The application module also tests the scheduler-neutral relay pass: fixed
publication outcomes, unexpected-exception redaction, capped exponential retry,
duplicate-claim rejection, and fenced ownership loss. This class is not yet
constructed by the gateway and therefore does not publish product traffic
(ADR-0350).
The PostgreSQL lifecycle test also verifies identity-free outbox status during
initial backlog, live leases, delayed retry, reclaimed attempts, and empty
completion. Focused gateway tests render fixed Prometheus gauges for backlog,
ready heads, leases, delay, retry, maximum attempts, and oldest age without
labels. The admin endpoint does not expose these gauges until relay composition.
The gateway lifecycle tests additionally prove immediate full-batch draining,
non-overlap, bounded idle/failure delays, reset after recovery, repeated-start
rejection, pending-work cancellation, and fixed relay counter rendering. The
loop is not constructed or started by the product listener.
Application routing tests cover the Redis-independent contract: complete
multi-gateway target resolution, payload-free stable hints, bounded stream size,
incomplete-target refusal, empty-route success, and whole-event retry after any
target dependency failure. No Redis client or production composition exists in
this slice (ADR-0351).
The following default-off gateway slice now registers type 119 behind negotiated
capability 5 and injects the PostgreSQL adapter through the product listener,
WebSocket upgrade, and authenticated pipeline. Handler tests prove server-bound
actor/device identity, bounded queue ownership, opaque denial/revision/conflict
mapping, acceptance correlation, and non-duplicate live publication. History
and process-local live tests prove capable peers receive `forwarded = true`
while legacy peers receive the same ordered message/body with the additive flag
cleared. Metrics expose only fixed `forward_accepted` and `forward_duplicate`
outcomes plus existing fixed denial/conflict/failure counters. The handshake
intentionally does not echo capability 5 yet, so this composed path remains
unreachable from product clients until their durable/UI gates pass.
The Web protocol client now has a matching default-off boundary. Only a client
constructed to request capability 5 can create a correlated type-119 command,
and only after exact capability negotiation and authentication. It enforces
canonical source/destination IDs, revision 0..100, and a stable destination
client message ID; the WebSocket transport forwards only those validated bytes.
Default clients also reject an unexpected inbound `forwarded` marker. Product
runtime composition still leaves capability 5 disabled while cache, outbox,
reconnect, presentation, and accessibility gates remain open (ADR-0344).
The isolated Web V2 IndexedDB record accepts the `forwarded` presentation flag
and a canonical local source conversation/message/revision pointer only for an
unresolved optimistic send. Sanitization drops malformed pointers and always
removes the pointer from accepted projections. It stores no source content,
server digest, credential, or temporary authorization. Dispatch/replay and UI
activation remain separate gates. The Web application boundary now hydrates a
directory-authorized destination cache before adding an optimistic forward,
persists the local-only source pointer before dispatch, and uses the existing
bounded stable-client-ID message replay. Authoritative history reconciles an
ACK-lost command and removes the pointer; cache-write failure prevents network
dispatch. Product runtime, destination picker, accessible presentation, and
capability activation remain separate gates.
The Web preview now includes a modal target-conversation list with native
buttons, labelled/ described dialog semantics, Escape/backdrop close, initial
close-button focus, a server-authority privacy explanation, a visible
`forwarded` marker, and existing message retry feedback. The application
snapshot hides this entry point unless its explicit default-false forwarding
option is enabled. `v2ForwardUi.test.mjs` locks the accessibility/source gate
and confirms the product runtime has not activated capability 5.
The application participant directory returns either a validated page or one
fixed authorization rejection. Its PostgreSQL adapter first proves that the
requester is an enabled active member, then pages enabled active participants
by account ID without a schema migration. The disposable PostgreSQL gate covers
departed/nonmember callers, departed-member filtering, Unicode names, and page
continuation (ADR-0343).
The dedicated participant handler consumes only type 117, requires an
authenticated capability-4 session, binds the requester from channel state,
serializes work through the bounded messaging executor, and returns fixed
invalid, unauthorized, busy, or internal outcomes. The product runtime now
injects the PostgreSQL port through the WebSocket upgrade and application
pipeline. Its pipeline integration gate negotiates capability 4, authenticates,
and proves a type-117 request reaches the injected participant port (ADR-0343).
The Web protocol boundary issues a correlated type-117 page
request and rejects unnegotiated, oversized, unordered, duplicate, malformed,
or cursor-inconsistent type-118 data. The WebSocket transport forwards this
command only through an authenticated capability-4 protocol client. The Web V2
preview advertises capability 4; supported V1 traffic and Windows remain
unchanged (ADR-0343).
The Web application layer exposes a conversation-scoped, maximum-500-member
transient view model with explicit refresh and load-more operations. It merges
pages by stable account ID, contains authorization failures, abandons ambiguous
requests on disconnect, and ignores responses after a conversation switch.
This state is deliberately not durable identity truth. The Web V2 mention
picker consumes it only as current server-authorized display data (ADR-0343).
Web application send, reply, and edit commands now accept already-composed
mention spans, reject any span set that no longer matches the exact UTF-8 text,
and retain the validated set in optimistic records and edit commands. Retry and
reconnect dispatch forward those same stable spans through the WebSocket
transport (ADR-0342/ADR-0343).
The framework-independent Web mention composer converts between editor UTF-16
positions and protocol UTF-8 bytes, shifts spans after non-overlapping edits,
invalidates a span when its visible token is edited, restores anchors from
stored spans, and segments rendering without deriving identity from display
text. Unicode-focused unit tests lock these rules (ADR-0342).
The Web V2 preview composes mentions from a keyboard-native participant dialog,
preserves cursor insertion across Unicode, highlights mention segments without
reparsing identity, exposes fixed loading/denial states, and advertises
capability 4. This activation applies only to the build-gated V2 preview; V1
and Windows behavior are unchanged (ADR-0342/ADR-0343).
The default-off Windows participant protocol boundary now emits type 117 and
strictly validates correlated type-118 pages, including conversation identity,
ascending account IDs, cursor advancement, roles, Unicode display-name bounds,
Unicode-only whitespace, and disconnect cleanup. It is compiled into the
Windows V2 messaging product library and composed behind an explicit controller
call, but is not exposed through Widgets or negotiated yet.
The cross-language protocol gate runs this test with Clang warnings-as-errors on
the macOS development host (ADR-0343).
The detached Windows participant view model keeps at most 500 current rows for
one active conversation, exposes explicit refresh/load-more state, sorts by
stable account ID, ignores pages and failures for another conversation, and
enters a fixed unavailable state on disconnect. Its Qt Core test runs with
warnings-as-errors. The messaging-controller test additionally proves that a
caller-triggered type-117 request shares the authenticated WSS, that only its
correlated type-118 response reaches the active conversation state, filters the
authenticated account, and that disconnect abandons the projection. Its resume
path proves that both the conversation directory and active participant
projection are requested again (ADR-0343).
`V2WindowsMentionComposerTest` locks the detached Windows editor model's Unicode
contract: mention insertion cannot split a UTF-16 surrogate pair, edits outside
a mention shift its anchor, edits inside it invalidate identity, and Qt UTF-16
positions round-trip to the protocol's UTF-8 byte spans. It also verifies that
render segmentation uses stored account identity instead of reparsing display
text. Passing this Qt Core test on a macOS development host is portability
evidence only; it is not the required Windows product gate.
`V2WindowsMessagingPanelTest` and `V2WindowsConversationDialogTest` additionally
exercise the default-off Widgets authoring seam. They prove that merely opening
a conversation does not fetch members, opening the picker explicitly requests
the active conversation, accessible keyboard selection inserts Unicode text,
and reply submission carries the exact account-backed UTF-8 span. The test also
keeps a default-off construction path so rollback can hide authoring without
discarding additive stored metadata.
The panel test also locks identity-preserving display and edit behavior: message
HTML is escaped before mention emphasis, assistive technology receives the
plain body, the highlighted segment retains its stored account target, and an
author edit restores then resubmits the original span after an unrelated suffix
change. Source activation additionally requires the session protocol to request
capability 4 and fail closed if the server omits it.
The `v2_windows_messaging_protocol_test` compiles the Windows C++ messaging
boundary against that same reviewed binding tree. It verifies exact
type-100/type-105 submission, stable ACK correlation, sequence history and live
reply projections, mutation, reaction, and pin-detail cursor advancement,
defensive UTF-8/reply/reaction/pin validation, and disconnect abandonment. The
boundary now also carries mentions through send/reply/edit and authoritative
history/live data while enforcing 20 spans, 10 distinct canonical targets,
ordered non-overlap, ASCII-`@` starts, and exact UTF-8 boundaries. Unicode
negative tests prove malformed inbound data fails without consuming its request
correlation. Source activation is covered by the separate session, controller,
and Widgets gates; Windows Release verification remains outstanding. It
also locks type-106/109 command identity, type-107/112 correlation, and
uncorrelated ordered type-108/113 events. The canonical default-off Windows CMake
product now composes this boundary with the shared authenticated Qt WSS,
account-isolated SQLite, a strict conversation directory, and Widgets surface;
the codec test alone remains protocol-boundary evidence (ADR-0331/ADR-0334–0338).
The codec also has a constructor-gated type-119 path. Tests lock canonical
source/destination IDs, revision 0..100, stable destination client-message-ID
correlation, acceptance, and capable `forwarded` projection; the default
constructor rejects both forwarding commands and unexpected markers. This is
protocol evidence only: the Windows session still requests exactly capabilities
1–4, and no SQLite/controller/Widgets path activates capability 5 (ADR-0344).
Windows local schema 7 adds a boolean presentation marker and three local-only
source command fields. The repository gate verifies restart recovery, exact
source/revision retry equality, changed-request rejection, and atomic source
identity erasure on acceptance while retaining `forwarded`. Accepted server
records cannot contain a source triple, and forwarding rows cannot carry reply
or mention metadata. The schema stores no source body, original sender, or
source timestamp.
The Windows application-service test now composes the enabled codec and schema
without product activation. It proves synchronized-source availability,
persist-before-type-119 dispatch, stable destination identity, ACK convergence,
and source-field erasure. A separate default construction rejects the action
before creating a destination outbox row. Existing pending-send reconnect and
denial behavior applies to forwarding through the same bounded queue.
The detached Windows ViewModel and Widgets panel now project the authoritative
`forwarded` flag as the accessible text marker `已转发`. The row deliberately
does not reveal the source conversation, source message, original sender, or
timestamp, and it adds no forwarding action while capability 5 remains disabled.
`V2WindowsMessagingViewModelTest` and `V2WindowsMessagingPanelTest` lock this
privacy-safe presentation boundary; the macOS run is portability evidence only.
`V2WindowsForwardTargetDialogTest` verifies the next detached/default-off UI
boundary. It filters empty and duplicate directory records, excludes the source
conversation, permits one destination, exposes native selection and accessible
labels, and fails closed when activation or source context is absent. The input
is the already-authorized conversation-directory snapshot; PostgreSQL remains
authoritative and rechecks both conversations when the command is eventually
submitted. The dialog is compiled into the Windows CMake product but has no
product action or capability activation yet.
The ViewModel and panel tests additionally compose that dialog into an enabled
test-only forwarding path. They prove that an accepted, non-recalled source is
required, self-forwarding is rejected, the exact hidden source/message/target
identities reach one callback, and default panel construction renders no
forward action even if a callback exists. Production controller composition and
session capability 5 remain intentionally absent, so this is not an activation
claim.
The Java composition root now wraps the durable PostgreSQL forwarding adapter in
a process-local account admission port. Defaults allow 120 attempts per
authenticated account per 60-second fixed window and retain at most 10,000
account keys. Operators may set `CHATROOM_GATEWAY_FORWARD_WINDOW_SECONDS` (1–3600),
`CHATROOM_GATEWAY_FORWARD_ATTEMPTS` (1–10,000), and
`CHATROOM_GATEWAY_FORWARD_MAX_KEYS` (16–1,000,000); invalid values fail startup.
Exhaustion or key saturation returns the standard retryable rate-limited error
without reaching PostgreSQL and increments only the fixed
`forward_rate_limited` messaging outcome. The limiter stores account UUID keys
in memory but emits no identity, source, target, or body labels/logs. It is a
single-node abuse guard; M5 distributed policy may replace it at the same port.
The Java history and live-router compatibility tests lock the downgrade path:
connections without capability 5 receive the same copied UTF-8 body and durable
conversation sequence while `forwarded` is cleared, so their cursor cannot
stall and they render an ordinary text message. Capability-5 connections see
the marker without receiving any source identity. The handler rejects type 119
before parsing or invoking the forwarding/admission port when capability 5 was
not negotiated. Web and C++ protocol tests independently reject unexpected
markers and keep forwarding construction default-off.
The gateway handshake has a separate default-deny forwarding policy input.
Capability 5 enters the connection attribute and `ServerHello` only when that
input is true and the client explicitly requested it. Existing pipeline calls
delegate with false, so compiling this seam cannot activate production. A later
runtime-composition step must provide reviewed configuration explicitly; turning
it off immediately stops new negotiations without changing stored messages.
The product composition now supplies that input from
`CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED`. It is false when absent, accepts
only exact lowercase `true` or `false`, and rejects any other value before bind.
Changing it requires a gateway restart; existing connections retain the
capabilities negotiated for that connection, while new connections immediately
follow the new policy. Client product paths remain off, so enabling only this
server setting does not expose an authoring action.
Web runtime composition now has its own exact build-time gate,
`VITE_CHAT_V2_MESSAGE_FORWARDING=true`. Missing, empty, or exact `false` keeps
both the protocol capability and application action off; any other value makes
the V2 runtime invalid. The same boolean is supplied to the protocol client and
application service, preventing a UI/protocol half-activation. A Web candidate
still succeeds only when the gateway independently enables and negotiates
capability 5.
Windows now has a separate CMake configuration seam,
`CHATROOM_ENABLE_WINDOWS_V2_FORWARDING=ON`. It is rejected unless both the
Windows client and `CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON` are selected. The
compiled `WindowsV2ProductConfiguration` exposes the immutable boolean; default
and ordinary preview builds keep it false. The Windows session codec and Qt WSS
transport accept the same default-false runtime input. The default `ClientHello`
and validation remain exactly
capabilities 1–4. Enabled construction appends capability 5 and rejects a
`ServerHello` that omits, reorders, or adds capabilities.
The Windows product composition carries the one immutable build value from
`main` through `ChatWindow`, the device/session controller, messaging service,
ViewModel, and Widgets dialog. A disabled transport rejects message type 119;
an enabled controller both negotiates capability 5 and installs the forwarding
callback before the UI can expose it. Because the CMake option remains `OFF`,
ordinary preview builds preserve the four-capability behavior. A Windows
Release build with the option enabled remains the product-platform gate; a
macOS protocol build is development evidence only.
CI compiles a separate, non-published forwarding-enabled `ChatClient` and runs
the target-dialog Widgets test without replacing the ordinary default-off
Windows verification payload. The Web baseline likewise compiles an enabled
preview into `build/m6/web-forwarding-gate` without uploading or promoting it.
These jobs prove buildability only; signing, native interaction, deployment,
and the activation evidence in the runbook remain separate release gates.
The Windows binary also exposes the side-effect-free
`--chatroom-print-v2-configuration-json` diagnostic. CI requires the ordinary
payload to report both V2 and forwarding disabled, and requires the separate
enabled candidate to report schema 1, the exact preview endpoint, and both
booleans true before accepting its compile gate. The JSON contains no device,
account, session, credential, or other secret state.
`V2LocalMessageRepositoryTest` exercises the separate default-off Windows V2
SQLite store through both qmake and CMake gates. It verifies restart-safe
pending replies, account isolation, exact ACK/history reconciliation, monotonic
cursors, atomic mutation-only history pages, live events that do not hide sync
gaps, explicit failed-versus-pending replay state, and the absence of copied
quote-body columns. This does not migrate or modify the V1 local database, and
the repository remains detached from product UI/transport (ADR-0332).
Schema 4 also verifies that ordered recall/deletion history mutations are
committed with their cursor: recalled text is erased and made non-replyable,
while administratively deleted targets are evicted. Existing reply rows retain
only their reference identity and can render the target as unavailable. Its
separate reaction projection and operation outbox verify account isolation,
persist-before-send optimistic state, restart recovery, explicit failed state,
idempotent acknowledgement, history convergence, and cursor monotonicity. Its
independent pin projection/outbox additionally verifies optimistic desired
state, failed retry, ACK-without-cursor-advance, ordered live convergence, and
account isolation. The completed application/UI slices now permit the Windows
handshake to advertise reaction, pin, and edit capabilities. Schema 5 adds a
separate bounded edit-command outbox and message revision metadata; tests cover
authoritative-content separation, exact reconnect replay, ACK cursor isolation,
history/live convergence, permanent conflict, explicit rebase with a new
operation ID, and discard.
Schema 6 adds normalized child tables for authoritative/pending-message mentions
and edit-outbox mentions. The repository regression proves Unicode span
validation, target-sensitive idempotency, restart-safe pending replay,
authoritative edit replacement, account isolation, and mention removal with
recall/deletion. Existing schema-5 rows migrate to empty mention sets; capability
4 stays off until the application and Widgets gates consume these values
(ADR-0342).
The Windows messaging application-service gate now additionally proves that
reply/edit mentions are persisted before send, replayed unchanged after
disconnect, restored from history/live records, retained through edit conflict
and rebase, and atomically converged on ACK. The existing ViewModel callbacks
still supply empty sets, so this boundary completion does not activate mention
authoring or capability 4.
`V2WindowsMessagingViewModelTest` now also locks the next presentation boundary:
non-recalled rows retain stable mention target/span values, and reply/edit
actions forward already-composed values without deriving identity from display
text. The current Widgets panel keeps using the default empty collection until
its composer and accessible picker gates are complete.
`v2_windows_messaging_application_test` composes the reviewed C++ codec and the
isolated SQLite store without opening a socket. It proves persist-before-send,
offline and reconnect replay with one client ID/target, bounded retryable
deferral, permanent failure plus explicit retry, ACK reconciliation, and
cursor-based atomic history merge. Reaction coverage additionally proves exact
operation replay, no-op ACK convergence, permanent failure plus explicit retry,
and ordered history/live repair. Pin coverage proves the same stable operation
identity and replay boundary, optimistic convergence, ACK cursor isolation, and
ordered live repair. Higher product tests cover Qt WSS routing,
directory selection, and Widgets composition (ADR-0333–0338).
`V2WindowsMessagingViewModelTest` verifies the presentation boundary independently
through qmake and CMake: cached-first projection, newline-safe quote previews,
reply selection and cancellation focus intent, failed-send retry eligibility,
explicit recalled/unavailable target labels, and pin state/failure/action
projection, and author-only edit overlays with pending, failed, and conflict
actions. The ViewModel has no socket or
SQL queries; the Windows product now composes it behind the runtime boundary.
`V2WindowsMessagingPanelTest` runs the reusable Widgets panel with the Qt
offscreen platform. It checks accessible names, keyboard-native reply/cancel/send
controls, composer enablement and focus flow. The mention extension adds an
explicit-load member picker, Unicode-safe insertion, account-backed submission,
escaped identity-preserving emphasis, and inline edit restore; the same test
keeps a default-off construction for rollback. Six checkable reaction controls
expose aggregate counts, caller state, pending disablement, accessible names,
and explicit retry while a separate checkable pin control exposes shared state,
pending disablement, accessible names, and failed-operation retry; the ViewModel
retains no SQL or socket access. Edit controls reuse the accessible inline
composer, show the edited marker and retained draft state, and expose explicit
retry, rebase, and discard actions. The
canonical Windows CMake
product compiles it behind the default-off V2 gate; the qmake rollback remains
V1-only. `V2WindowsConversationDialogTest` verifies accessible directory and
paging controls, user-facing unread rows, hidden authorized identity selection,
and cached-message rendering (ADR-0338).
`windows_v2_product_composition_test.py`, which is part of the Windows artifact
policy step, locks the final source wiring: the product dialog enables mention
authoring, the session requests and strictly validates capability 4, the
controller filters self and repairs participant state after resume, and the
panel serializes and renders stored identity spans. The following Windows
Release build and offscreen Qt tests remain the platform evidence.
Gateway tests separately verify authenticated server-bound identity,
off-event-loop submit/history dispatch, per-connection ordering, safe denial,
bounded saturation behavior, and isolation from the authentication worker pool.
They also verify that final authorized history establishes a process-local active
subscription without a snapshot/stream gap, a new durable acceptance emits type
104 after ACK, duplicates do not republish, closed routes are removed, and live
or slow-consumer outcomes use fixed-cardinality counters.
This is pre-cutover evidence, not product traffic or a capacity result.
Web V2 tests also exercise the inactive attachment register/authorize/direct-
PUT/complete coordinator. They use injected transports and fetch responses, do
not contact object storage, and prove transient grant/byte cleanup rather than a
real-provider acceptance result.
Loopback administration tests also verify fixed-cardinality accepted, duplicate,
history, denial, conflict, saturation, and failure counters plus message worker
active/queue gauges; no identity or conversation value is a metric label.
The authentication worker tests use a one-worker/one-slot pool to prove bounded
admission, saturation shedding, worker naming, and lifecycle shutdown. These
test capacities are not deployment defaults or benchmark results.
Virtual-time gateway tests verify independent handshake/authentication deadlines,
fixed WebSocket 1008 close reasons, phase transitions, and timer cancellation;
their millisecond test values are not deployment defaults.
Deterministic admission tests verify account normalization, direct IPv4/IPv6
peer aggregation, gateway totals, bounded key capacity, success/expiry recovery,
and non-identifying snapshots. These process-local tests do not prove distributed
rate limiting or define deployment limits.
Telemetry tests verify fixed-label counters, authentication execution-duration
buckets, total/maximum duration, credential-upgrade debt, and exact power-of-two
warning sampling without account or peer data. They do not represent a latency
benchmark or a deployed monitoring backend.
Gateway operations tests open only an ephemeral loopback admin port and verify
GET-only liveness/readiness/Prometheus paths, explicit readiness transitions,
bounded labels, cumulative duration buckets, response hardening, wildcard-bind
rejection, and deterministic shutdown. `GatewayMain` owns this server, but the
exact-path loopback boundary remains a deployment contract rather than public API.
Trusted-proxy policy tests verify direct-header spoofing is ignored, configured
IPv4/IPv6 CIDRs use bounded right-to-left forwarding-chain resolution, and
trusted missing, hostname, invalid, or excessive forwarding chains fail closed.
Pre-upgrade handler tests verify direct/proxied canonical address freezing,
generic rejection for missing trusted forwarding and repeat requests, reference
ownership, and consumption by authentication admission. The WSS component and
`GatewayMain` install the handler in the verified order.
Endpoint-policy tests verify exact `/v2/web` and `/v2/windows` upgrade shapes,
HTTPS Web Origin normalization/allowlisting, browser-origin rejection on the
Windows route, malformed/repeated upgrade failure, and later ClientHello
platform matching. They also require the fixed `chat.v2` subprotocol.
Post-upgrade composition tests assert bounded frame/phase/authentication handler
order and authenticated reader-idle closure with a fixed WebSocket 1001 outcome.
The policy and pipeline do not activate a product route.
Host-policy tests verify one exact configured TLS authority, default-port/case
normalization, IPv6/non-default ports, missing/duplicate/hostile rejection, and
single-request ownership before upgrade.
Runtime configuration tests use temporary placeholder TLS files and prove all
critical settings validate before bind, admin stays loopback, DNS names are not
resolved for listener addresses, unsafe/missing values fail, and configured
passwords do not appear in object text. They also prove remote PostgreSQL
`verify-full` enforcement, the explicit numeric-loopback development exception,
bounded pool settings, duplicate/embedded-credential rejection, and secret-safe
pool text without opening a database connection. Listener integration tests use an
ephemeral loopback port and test-only certificate to prove a real TLS handshake,
`chat.v2` 101 upgrade, missing-subprotocol 400 rejection, connection limiting,
upgrade timeout, and deterministic shutdown. This is local transport evidence,
not trusted-certificate or production-route evidence.
Gateway resume tests verify bounded off-event-loop dispatch, rotated proof
delivery, server-side identity binding, secret cleanup, generic invalid-proof
rejection, and gateway/direct-peer admission without creating a fake account
limiter key. They do not open a listener.
It also verifies a fixed libsodium 1.0.20 Argon2id interactive test vector with
the locked Java crypto adapter; this deliberately performs memory-hard work and
must not be interpreted as an authentication capacity benchmark.
The same Java gate verifies exact V1 salted-SHA compatibility and current-policy
Argon2id rehashing. The PostgreSQL gate applies V001 through V004 and checks
credential shape constraints, compare-and-set upgrade behavior, digest-only session proof
rotation, sequential/concurrent replay denial, device binding, expiry, and
revocation against a disposable real database. It then constructs the real
`GatewayRuntime`, validates migration state and the HikariCP pool, starts admin
and WSS on ephemeral loopback ports, observes ready state, and closes every
resource before deleting the cluster. This is composition evidence, not product
traffic or capacity evidence.
The same disposable PostgreSQL run races two exact message submissions and
verifies one original plus one stable duplicate, rollback of the losing sequence
allocation, conflict rejection, database-authoritative acceptance time, bounded
ascending cursor pages, and active-membership authorization. The runtime now
exposes this adapter to already authenticated V2 connections for text submission
and sequence-history reads. No supported client uses that route. Process-local
active-conversation fan-out is implemented for the build-gated preview; durable
delivery/read semantics and multi-gateway routing remain unimplemented.
The same real-database gate verifies the transport-independent conversation
directory: group/direct labels, role and sequence projection, stable composite-
cursor paging, left-membership filtering, and disabled-account denial. Its wire
command is now composed in the real runtime. Embedded-channel tests separately
prove server-bound account identity, bounded response projection, serial command
execution, safe dependency failure, and fixed directory-page telemetry.
Pure migration-planner tests also verify deterministic V1 user-ID mapping,
order-independent source fingerprints, supported credential generations, full
plan blocking on invalid/duplicate/empty input, and non-secret issue reporting.
The locked Xerial SQLite JDBC migration adapter tests a WAL-mode, query-only V1
source, `quick_check`, required startup-migration columns, both supported UTC
timestamp forms, safe invalid-time reporting, and fixed pre-migration schema
failure. Reading is not backup or cutover evidence.
Online-backup tests use the SQLite backup API against an open WAL source, reopen
and reconcile the artifact, verify hash/size/time proof, refuse overwrite, and
remove incomplete output. Test artifacts are not production restore evidence.
The migration CLI also provides a PostgreSQL-independent `verify-final` gate that
reconciles the current source, restored backup, proof, and explicit fingerprint;
tests prove post-backup source drift fails safely. This does not prove V1 writer
quiescence or replace a timed full-server restore rehearsal.
The real-PostgreSQL identity-import gate previews without writes, requires the
reverified source/backup proof, atomically imports both credential generations,
reconciles every target field, persists safe proof counts, repeats without
duplicate accounts, and proves target conflicts leave account/audit state
unchanged. This is an inactive adapter test, not an operator cutover command.
The separate `migration-cli` module exposes offline identity,
`conversation-*`, and `message-*` preview/final-verify/apply actions. It also
provides `profile-image-export`, which verifies a protected backup/proof pair
and atomically emits a deterministic manifest plus bounded canonical PNG
objects. `profile-image-verify` independently requires the retained manifest
hash and rejects proof/record/object/tree disagreement. Neither command uploads
objects or writes PostgreSQL. `profile-image-preview` then repeats strict bundle
verification and checks mappings, pointer conflicts, registered object evidence,
cleanup claims, and prior runs without provider access or database writes.
An inactive migration upload component rechecks every unique object immediately
before invoking the checksum-bound create-only writer and emits an apply
capability only when all Provider evidence is exact. The persistence adapter can
atomically apply that capability and reconcile exact retries/restarts. The
guarded `profile-image-apply` command
composes verification, target preview, every-object Provider convergence,
post-upload re-verification, and atomic apply; it requires explicit import,
credential-provider, and reviewed-evidence confirmations and is never run by CI.
Message apply requires separate state and payload fingerprint confirmations.
Unit tests verify the versioned proof artifact and safe output; the PostgreSQL
verifier then exercises the ordered identity/conversation/message command
boundary serially after repository tests. Operator procedure and stop conditions are documented in
`docs/deployment/V1_IDENTITY_IMPORT_RUNBOOK.md`.

Run the V2 PostgreSQL migration gate with local PostgreSQL server tools
(`initdb`, `pg_ctl`, and `createdb`) available either on `PATH` or through
`pg_config --bindir`:

```bash
python3 tools/verify_m0.py --postgres
```

The verifier creates a trust-authenticated, disposable cluster under `/tmp`,
listens only on `127.0.0.1` at a random port, migrates a clean database, validates
a same-database restart, exercises sequence/idempotency constraints, stops the
server, and deletes the cluster. It also proves exact username lookup, stable
device reuse, digest-only session tokens, and revoked/disabled denial. It never
reads or modifies a developer or production database. CI runs the same gate
using the PostgreSQL tools bundled
with the Ubuntu runner.
V044 additionally verifies immutable reply-reference creation from a live
same-conversation target, exact duplicate replay, changed-target conflict,
missing-target denial, and authoritative history projection (ADR-0329).
V045 verifies actor-scoped reaction operation idempotency, exact concurrent
retry convergence, durable active-state projection, changed-only sequence/event
allocation, no-op cursor stability, mixed-history projection, opaque target and
actor denial, and target-deletion cleanup (ADR-0339). It does not activate the
supported client feature. Gateway tests separately prove explicit capability
echo, pre-capability command rejection, server-bound actor/device identity,
capable-only live fan-out, legacy-history detail filtering with cursor
advancement, and fixed-cardinality outcomes.
V046 verifies shared message-pin desired-state idempotency, changed-only mixed
history, opaque authorization failure, durable current-state projection, and
same-transaction ordered recall cleanup. The schema also implements durable
50-pin rejection, GROUP OWNER/ADMIN mutation policy, and ordered V2 deletion
cleanup. Gateway tests cover pre-capability rejection, server-bound identity,
fixed response mapping, capable history detail, and capable-only live fan-out.
Windows pin capability activation still waits for its local projection
(ADR-0340). Web tests cover command correlation, ACK/history/live validation,
IndexedDB operation replay, ACK-without-cursor-advance, optimistic projection,
target cleanup, and accessible retry controls. Web ClientHello now advertises
`MESSAGE_PINS` because that complete local slice is connected.

Generate the non-Java V2 bindings and run the Java-to-TypeScript golden-wire
test with Node.js 22:

```bash
python3 tools/verify_m0.py --protocol-bindings
```

This installs the lockfile-pinned generator, invokes the same Protobuf 4.35.1
compiler used by Gradle, and republishes the reviewed TypeScript and C++ trees
committed under `WebClient/src/protocol/v2/generated/` and
`Client/protocol/v2/generated/`. The gate fails when regenerated bytes differ
from either committed tree. It then compiles those exact C++ files against
SHA-256-pinned Protobuf 35.1 and Abseil 20250512.1 sources, requires Java,
TypeScript, and C++ to parse/emit the same golden envelope, and runs the pure
C++ Windows device-management and session-protocol regressions. These cover
exact-version Windows hello, fresh authentication, memory-only resume, session
authority, authenticated device-codec composition, and the detached Qt WSS
lifecycle. The Qt regression checks exact `wss://.../v2/windows` routing,
`chat.v2` negotiation, binary framing, authentication, and Qt projection. The
first run downloads and builds the isolated C++ runtime. The supported Windows
CMake product compiles the same sources and statically links the same pinned
runtime. The application service caps a
pending fresh-login credential at 60 seconds, consumes it once, clears live
directory work on disconnect, and writes neither the password nor the device
directory to durable storage (ADR-0325).
The V2 ClientHello uses one account-independent installation UUID from an
owner-only atomic file under the application-local security directory. Corrupt
or unsafe existing identity state fails closed instead of silently creating a
new server device (ADR-0326).
The canonical Windows client composes these boundaries only when the reviewed
V2 build configuration is enabled. A successful V1 login clears its password
field and transfers the UTF-8 bytes once to the V2 controller; invalid/disabled
configuration erases them without starting V2. The “登录设备” settings entry is
hidden by default, appears only after controller startup, refreshes authoritative
live state when opened, and stops before V1 logout. V2 failure does not break V1
chat. After V2 authentication, “新版会话与回复（预览）” exposes authorized
conversation names and unread state, cached-first history, optimistic replies,
and exact-cursor paging; cached conversations remain reachable while reconnecting.
The qmake rollback remains V1-only (ADR-0327/ADR-0338).

## Server Password Hashing Dependency

All server and database test targets require libsodium. The macOS and Ubuntu
entries below support local development, server, and CI verification; they do
not make those systems supported client products:

- macOS/Homebrew: `brew install libsodium`;
- Ubuntu 24.04: `sudo apt install libsodium-dev pkg-config`;
- Windows/MSVC: run `vcpkg install --triplet x64-windows` from the repository,
  then set `SODIUM_ROOT` to `vcpkg_installed/x64-windows` before running qmake.

The authentication migration regression is:

```bash
python3 tools/verify_m0.py --password-hash
```

It covers new Argon2id account and room secrets, correct/incorrect verification,
successful legacy SHA-256 account and plaintext-room upgrade, wrong-password
non-mutation, password change, parameter-driven rehash, restart, and clearing.

## SQLite Schema Regression

Requires Qt Core, Qt SQL, the SQLite Qt driver, libsodium, qmake, and a platform
compiler:

```bash
python3 tools/verify_m0.py --db-schema
```

The test creates an isolated temporary database, verifies every required V1
table and migrated column after the first initialization, runs
`PRAGMA integrity_check`, initializes again to simulate a restart, and requires
both schema snapshots to be identical.

It also runs `EXPLAIN QUERY PLAN` assertions for the critical membership,
unread, room-file, friend-request, and friendship lookup indexes. An index name
present in `sqlite_master` is not considered sufficient unless the intended
query shape actually uses it.

This regression runs as its own Ubuntu 24.04 CI job. It intentionally avoids Qt
GUI dependencies so database validation is independent of desktop packaging and
graphics toolchains.

## V1 TCP Smoke Test

Requires Qt Core, Network, SQL, WebSockets, the SQLite driver, qmake, and a
platform compiler:

```bash
python3 tools/verify_m0.py --v1-smoke
```

The command builds `Tests/HeadlessServer.pro` from the production server sources
with only server-side image thumbnail generation disabled. It then launches the
server on an isolated local three-port range with a temporary database and file
directory.

The positive smoke client verifies registration, login, room creation/join,
authenticated sender identity, message fan-out, history persistence, file
notification metadata, disconnect/reconnect, persistent membership,
post-reconnect delivery, and recall. A second three-user suite verifies negative
authorization for room data/settings, cross-room recall, message/file writes,
upload ownership, TCP downloads, and HTTP downloads. A third suite verifies
oversized/malformed frames, envelopes, connection message rate, the 8 MiB inline
compatibility boundary, and slow-consumer disconnection. None uses production
ports, credentials, databases, or COS.

A fourth suite rejects oversized passwords/messages, unbounded history counts,
unsafe filenames, Base64/declared-size mismatches, chunk overflow, incomplete
uploads, and more than five expensive authentication commands per connection.

A fifth suite launches isolated servers with short test windows and verifies
that account, direct-peer IP, and process/gateway authentication limits span
independent TCP/WebSocket connections, recover after expiry, preserve existing
V1 response types, emit structured denial counters, and do not log the test
password.

A sixth suite verifies V1 room-message durable acceptance, duplicate and
conflicting retries, old-client envelope compatibility, authorization failure,
per-room sequence resume, process restart, interrupted sequence backfill,
deleted-high-watermark monotonicity, and structured messaging outcome counters.

A seventh suite applies the same retry/conflict, authorization, restart,
partial-migration, high-watermark, and ordered-resume checks to friend
text/emoji messages.

An eighth suite verifies replayable room/direct recall, stable retry outcomes,
incremental `syncSequence` state recovery, and process-restart durability.

The ninth suite verifies administrative deletion authorization, bounded
selection, all four modes, exact/conflicting retry, unified cursor pagination,
offline replay, and process-restart durability.

A tenth suite verifies the raw HTTP attachment data plane: owner-token
binding, exact length, foreign-token denial, interrupted-body cleanup,
room/friend idempotent finalization, duplicate/conflict responses, retry after
restart, and notification frames without inline file bytes.
The Qt gate additionally compiles and runs `HttpUploadTransportTest`,
`HttpDownloadTransportTest`, `MessageModelTest`, `NetworkReconnectTest`, and
`LocalConversationRepositoryTest`.
The reconnect test uses a local fake server to verify that a dropped Windows
session reauthenticates before publishing restored connectivity. The V1 smoke gate runs
them with loopback access where required and checks the real raw `PUT`/`GET`
plus stable-ID state reconciliation. A source contract
test keeps Windows room/file/image and friend composer entry points on the
upload-session path and upgraded forwarding on server file identity.

The native Windows artifact gate also requires `windeployqt` to include
`sqldrivers/qsqlite.dll`; compiling `QtSql` without its runtime driver is not
accepted as local-repository delivery evidence.

An eleventh V1 suite verifies server-side room/friend file forwarding, copied-byte
integrity, notifications without inline bytes, source authorization, target
authorization, partial-result accounting, and durable live sequence metadata.
The base smoke and HTTP-upload suites also require database timestamps and
positive room sequences on their live attachment notifications.

CI runs the same smoke test on Ubuntu 24.04 after installing Qt Base, Qt SQLite,
Qt WebSockets, and libsodium development packages.

## V1 Identity Restore Rehearsal

Requires the headless Qt server dependencies above plus Java 21:

```bash
python3 tools/verify_m0.py --v1-identity-restore
```

The command builds the matching C++ V1 server and Java migration CLI, creates a
temporary V1 source through the real TCP API, adds a controlled legacy SHA-256
fixture only after the source process exits, creates and verifies an online
backup, and launches the same server against an isolated restored copy. It then
proves that Argon2id and legacy accounts can log in and that message history is
present. The source, backup, proof, restored database, and test credentials are
temporary.

The generated `build/m0/<host>/v1-identity-restore-evidence.json` records the
server digest, non-secret source fingerprint, identity count, tested credential
generations, history result, and bounded phase timings. It deliberately records
`production_writer_quiescence_verified=false`: the harness proves only that its
owned source process exited. CI archives this non-secret evidence for 14 days.
Production cutover still requires independent operator evidence that every V1
writer was stopped for the maintenance window.

## V1 Authentication-abuse Configuration

The current single-node server reads these optional process environment
variables before accepting authentication work:

| Variable | Default | Purpose |
| --- | ---: | --- |
| `CHATROOM_AUTH_WINDOW_MS` | 60000 | Fixed-window duration |
| `CHATROOM_AUTH_GATEWAY_ATTEMPTS` | 600 | Total process authentication attempts per window |
| `CHATROOM_AUTH_IP_ATTEMPTS` | 60 | Attempts per direct transport peer per window |
| `CHATROOM_AUTH_ACCOUNT_ATTEMPTS` | 10 | Attempts per normalized account per window |
| `CHATROOM_AUTH_MAX_TRACKED_KEYS` | 4096 | Bound for active IP and account keys |

The startup log reports the effective non-secret configuration. Sampled denial
logs contain operation, limiting dimension, retry duration, aggregate counters,
and active-key counts without account IDs, peer addresses, passwords, or
payloads. The first and power-of-two cumulative denials per dimension are logged
to prevent linear log amplification.
The IP key is the direct TCP/WebSocket peer; no unverified forwarding header is
trusted. Behind a reverse proxy, size this limit for the proxy's aggregate load
until a trusted-proxy design is introduced.

## V1 Performance Baseline

The performance scenario uses the same production server sources as the TCP
smoke test. It creates authenticated clients on loopback, joins them to one
room, sends warm-up messages, and then records message persistence/fan-out:

```bash
python3 tools/verify_m0.py --performance
```

The default JSON result is written to
`build/m0/<platform>/v1-performance.json`. To create a reviewed, stored baseline:

```bash
python3 tools/verify_m0.py --performance \
  --performance-clients 8 \
  --performance-warmup 20 \
  --performance-messages 100 \
  --performance-output docs/baselines/M0_PERFORMANCE_YYYY-MM-DD.json
```

The result records the exact scenario and environment, send-to-own-echo latency,
SQLite `saveMessage` latency, accepted-message and fan-out throughput, sampled
server CPU/RSS, and available artifact sizes. It is a regression reference, not
a production capacity promise: loopback networking, sequential acknowledgements,
and a single process intentionally make the run small and repeatable.

CI executes a shorter version to ensure the harness remains operational. It
does not enforce a latency threshold on shared hosted runners.

## Qt Server and Desktop Client

Requires Qt with Core, GUI, Widgets, Network, SQL, WebSockets, and Multimedia,
plus libsodium, qmake, and the platform compiler:

```bash
python3 tools/verify_m0.py --qt
```

Set `QMAKE` when several Qt installations exist:

```bash
QMAKE=/path/to/qmake6 python3 tools/verify_m0.py --qt
```

Build products are written below ignored `build/m0/<platform>/` directories.

The current desktop product target is Windows. A native Windows Release build is
product-gate evidence; Qt builds on macOS or Linux are development/portability
evidence only. Server deployment and headless test hosts are independent of the
supported client operating systems.

Use a Qt build that officially supports the selected operating-system SDK. Do
not patch a global Qt installation or silently remove framework dependencies to
make a local build appear successful.

## Full Local Verification

```bash
python3 tools/verify_m0.py --all
```

The full command includes inventory, Web, Java, SQLite schema, V1 smoke, and
both Qt source targets. It is expected to fail when a required compiler, JDK,
Qt module, or host SDK is unavailable. Report that environment limitation and
retain the successful component results. On non-Windows hosts this is local
development/portability verification, not a supported desktop release gate.

## Windows Notes

- Run from a Qt/Visual Studio or Qt/MinGW developer environment.
- The verifier selects `nmake` for an MSVC qmake spec and
  `mingw32-make`/`make` otherwise.
- Native CI uses MSVC 2022 and pinned Qt 6.11.1, builds both qmake fallback and
  canonical CMake targets, proves their deployed runtime parity, then promotes
  the CMake `windeployqt` directory as the short-lived client-only unsigned
  verification payload. Root `VERSION` supplies both the Qt application version
  and artifact version.
- CI writes deterministic `artifact-manifest.json` and `SHA256SUMS` metadata
  containing schema-4 `buildSystem: cmake`, the exact Git revision, toolchain
  identity, parity-evidence hash, file sizes, and hashes.
  Run its cross-platform policy test with
  `python3 Tests/windows_artifact_manifest_test.py`.
- The native job also pins NSIS 3.12, compiles an explicitly unsigned per-user
  `Setup.exe`, silently installs it in an isolated directory, validates version,
  SQLite runtime and HKCU uninstall metadata, then uninstalls it while proving
  account-local data survives. Its source-policy test is
  `python3 Tests/windows_installer_policy_test.py`.
- The same job invokes the provider-neutral release-signature verifier against
  unsigned payloads and a renamed Setup, and requires fail-closed rejection with
  no evidence. Positive client/helper/uninstaller/Setup signature evidence is reserved for
  a protected release job with external key custody.
- A protected release job must independently run release-evidence validation,
  assemble the complete immutable candidate, and run candidate verification
  before any upload or promotion. Later update-manifest signing must hash the
  Setup inside that candidate.
- A signed installer, upgrade/uninstall behavior, and automatic updates are
  still separate M4 release concerns. This CI Setup exercises install/uninstall
  mechanics but is not a publisher-signed or publicly supported installer.
- `packaging/windows/support-matrix-policy.json` pins the initial client hosts
  to Windows 10 22H2 build 19045 and Windows 11 23H2/24H2 builds 22631/26100.
  `tools/windows_support_host_evidence.py` rejects Windows Server and binds all
  clean-host, real-prior-version launch/upgrade, AppData preservation, current
  launch, running-upgrade/downgrade rejection, uninstall, and cleanup results to
  both complete signed candidates. Fixture verification is not native host
  evidence; see ADR-0201.
- `.github/workflows/m4-windows-support-matrix.yml` consumes exact previous and
  current protected-signing artifacts on dedicated ProductType-1 clean client
  runners. It revalidates both candidates, executes the complete transition,
  independently verifies the output, and retains one record per target. It has
  no signing or publication authority. A checked-in workflow is not a passing
  matrix; provision/reset the named hosts and retain a successful reviewed run
  before making a Windows support claim. See ADR-0202.
- The workflow's Ubuntu `close-matrix` job redownloads both candidates and all
  three host records, reconstructs every check with
  `windows_support_matrix_completion.py`, and retains one 90-day immutable
  completion. Results from different versions/runs cannot be combined. This
  still needs a real successful workflow run; see ADR-0203.

## macOS Development-host Notes

- Install Xcode command-line tools and a Qt build compatible with the active SDK.
- macOS may be used for fast local server, Web, test, and Qt portability
  feedback because it is the maintainer's development host.
- Any retained macOS CI output is explicitly non-product development evidence.
  The current roadmap does not include a macOS installer, signing,
  notarization, stapling, DMG, update channel, or compatibility promise.
