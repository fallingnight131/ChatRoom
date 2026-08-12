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

CI runs inventory and web verification on every push and pull request through
`.github/workflows/m0-baseline.yml`.

Windows product verification and non-product Qt portability builds run through
`.github/workflows/m0-product-builds.yml`. The supported client scope and the
distinction between product and development hosts are documented in
`docs/architecture/SUPPORT_MATRIX.md`.

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
Gateway tests also drive the inactive cleanup loop through a deterministic
manual scheduler and verify healthy cadence, capped failure backoff, recovery,
close behavior, and fixed-cardinality `/metrics` output without starting a
thread or contacting object storage.
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
three generated bindings. The generation task also publishes reviewed TypeScript
into `WebClient/src/protocol/v2/generated`; the gate snapshots those committed
files and fails if regeneration changes them. Do not edit generated Web files
manually. Gateway tests separately verify authenticated server-bound identity,
off-event-loop submit/history dispatch, per-connection ordering, safe denial,
bounded saturation behavior, and isolation from the authentication worker pool.
They also verify that final authorized history establishes a process-local active
subscription without a snapshot/stream gap, a new durable acceptance emits type
104 after ACK, duplicates do not republish, closed routes are removed, and live
or slow-consumer outcomes use fixed-cardinality counters.
This is pre-cutover evidence, not product traffic or a capacity result.
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
`conversation-*`, and `message-*` preview/final-verify/apply actions. Message
apply requires separate state and payload fingerprint confirmations. Unit tests
verify the versioned proof artifact and safe output; the PostgreSQL verifier
then exercises the ordered identity/conversation/message command boundary
serially after repository tests. Operator procedure and stop conditions are documented in
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
compiler used by Gradle, and writes C++, TypeScript, and descriptor output below
ignored generated/build directories. Generated files are never edited or
committed. It then compiles the generated C++ against SHA-256-pinned Protobuf
35.1 and Abseil 20250512.1 source archives and requires Java, TypeScript, and C++
to parse/emit the same golden envelope. The first run downloads and builds the
isolated C++ test runtime; it is not linked into the current Qt product.

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
- Native CI uses MSVC 2022 and pinned Qt 6.11.1, then runs `windeployqt` to
  assemble a short-lived unsigned verification artifact.
- A signed installer, upgrade/uninstall behavior, and automatic updates are
  separate M4 concerns.

## macOS Development-host Notes

- Install Xcode command-line tools and a Qt build compatible with the active SDK.
- macOS may be used for fast local server, Web, test, and Qt portability
  feedback because it is the maintainer's development host.
- Any retained macOS CI output is explicitly non-product development evidence.
  The current roadmap does not include a macOS installer, signing,
  notarization, stapling, DMG, update channel, or compatibility promise.
