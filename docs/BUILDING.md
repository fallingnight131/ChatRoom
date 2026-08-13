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
VITE_CHAT_V2_WSS_URL=wss://preview-chat.example.com/v2/web \
VITE_CHAT_APP_VERSION=2.0.0-preview.1 \
npm run build
```

These values are public build metadata, never secrets. The enabled build emits
V2 as a lazy chunk and still sends no V2 traffic until a preview UI explicitly
starts it. See [`WEB_V2_PREVIEW.md`](deployment/WEB_V2_PREVIEW.md) for gateway
alignment, verification, and rollback.

## Incremental CMake server path

The root `CMakeLists.txt` currently represents the V1 persistence/server-core
libraries, shared V1 Common, non-UI Windows client local-data and transport
libraries, portable Windows update trust/transport boundaries, thin
`ChatServerHeadless`, and twenty-nine CTest entries. Together they compile
the same Common/Server/client-core sources as the qmake projects and do not
replace the Windows product build or installer.
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
types 100..104 and content type 1 (bounded nonempty UTF-8 text). Type 104 is the
uncorrelated authenticated live `MessageRecord` event. It verifies the
fixed `SubmitMessage` golden payload in Java, generated TypeScript, and generated
C++. It also locks the V2 conversation-directory composite cursor across all
three generated bindings. The generation task also publishes reviewed
TypeScript into `WebClient/src/protocol/v2/generated` and reviewed Windows C++
into `Client/protocol/v2/generated/chat/v2`. The gate snapshots both committed
trees, regenerates, and fails if either changes. Do not edit generated client
files manually. The C++ golden test compiles the committed Windows tree rather
than an ignored temporary copy. Gateway tests separately verify authenticated server-bound identity,
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
C++ Windows device-management protocol regression. The first run downloads and
builds the isolated C++ runtime. The codec is not yet composed into the current
Qt product; V2 WSS/session activation remains a separate change.

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
