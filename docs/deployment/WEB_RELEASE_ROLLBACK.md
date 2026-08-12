# Web Immutable Release and Rollback Runbook

This runbook defines the provider-neutral filesystem contract used before a Web
hosting adapter is allowed to receive public traffic. It does not authorize a
production deployment by itself.

## Release layout

```text
<store>/
  active-release.json
  releases/
    <semver>-<40-char-git-sha>/
      site/
      response-policy.json
      web-artifact-manifest.json
      SHA256SUMS
```

Release directories are immutable. `active-release.json` is the only mutable
selection state and is replaced atomically. A hosting adapter serves
`site/index.html` and assets from the selected directory, applies that same
directory's bound `response-policy.json`, and substitutes the selected version
and source revision into the release identity response headers.

## Stage

Start from the complete CI artifact, not a bare `WebClient/dist` directory:

```bash
python3 tools/web_release_store.py stage \
  --artifact-root /path/to/chat-room-web-artifact \
  --store-root /srv/chat-room-web
```

Staging validates every declared file size and SHA-256, the exact checksum set,
the bound response policy, absence of undeclared files, and release identity.
It copies into a temporary sibling, validates again, and renames the directory
into place. Re-staging exactly matching bytes is idempotent; conflicting or
tampered content fails.

## Activate and verify

```bash
python3 tools/web_release_store.py activate \
  --store-root /srv/chat-room-web \
  --release-id 1.0.0-0123456789abcdef0123456789abcdef01234567

python3 tools/web_release_store.py status \
  --store-root /srv/chat-room-web
```

The status command rereads and hashes the selected immutable release, compares
the pointer's version, Git revision, entrypoint, policy digest, and file count,
then emits `status: healthy`. A real hosting adapter must additionally probe its
HTTPS URL, observe every bound response/cache/identity header, open `/ws`, and
check `/api/` before promotion. Those live checks and browser gates are not
implemented by this filesystem tool.

When an HTTPS adapter is available, observe the selected external response
contract before and after activation. For a private acceptance CA, pass its
certificate explicitly; production normally uses the system trust store:

```bash
python3 tools/web_release_probe.py \
  --base-url https://chat.example.com \
  --release-root /srv/chat-room-web/releases/<release-id> \
  --output /path/to/evidence/web-release-probe.json
```

The probe refuses HTTP, redirects, invalid certificates, altered bytes, missing
or duplicate policy headers, wrong cache classes, cookies, CORS, and unbound
compression when identity encoding was requested. It reads every static file
declared by the immutable manifest.
Keep the JSON evidence with the rollout record; it contains identities and
paths, not response bodies or credentials.
The output is atomically created once and refuses replacement. Reverify the
retained record against the same immutable release and origin with:

```bash
python3 tools/web_release_probe.py \
  --base-url https://chat.example.com \
  --release-root /srv/chat-room-web/releases/<release-id> \
  --verify-evidence /path/to/evidence/web-release-A.json
```

Before promotion, serve candidate B on a dedicated preview HTTPS origin and
prove that preview authority reaches the V1 application routes without
credentials:

```bash
python3 tools/web_application_route_probe.py \
  --base-url https://preview-chat.example.com \
  --output /path/to/evidence/web-application-routes.json
```

This requires exact `/api/health` process/route identity and a valid random-
challenge WebSocket upgrade at `/ws`. If the Web build uses a reviewed custom
same-origin WebSocket path, pass that exact path with `--websocket-path`.

Join the preview candidate static/route observations with exact B and the
different retained production rollback A using `tools/web_promotion_evidence.py
record`; independently reconstruct it with the `verify` command before any
provider adapter changes traffic. The complete command is in `docs/BUILDING.md`.
Schema 2 requires preview and production origins to differ and derives the
production target from A's retained observation. It never requires B to occupy
the production root before approval.

The filesystem adapter selects B for preview with
`tools/web_preview_release.py select`. This atomically changes only
`preview-release.json`; production continues to read `active-release.json`.
Both selectors reference the same immutable `releases/<release-id>/` tree.
The result says `technical-gates-observed-not-published`; it is not proof that
traffic changed or that an incident rollback met its recovery objective.

Before a provider adapter changes traffic, a reviewer protected by the
`web-production` environment must create and immediately reverify a short-lived
`web_release_authorization.py` record using that exact technical promotion and
all five bound inputs. The authorization is valid for at most 15 minutes,
refuses a technical approval older than 15 minutes, contains no provider
credential, and says `production-promotion-approved-not-executed`. The adapter
must consume it once and record separate provider evidence; the authorization
itself is not deployment evidence.

For deployments whose hosting layer reads `active-release.json`, consume the
authorization with `web_release_execution.py execute`. The adapter refuses to
switch unless the current pointer is the authorized rollback release, writes a
non-replay marker before mutation, and restores that pointer if local health or
evidence persistence fails. A successful record still says
`pointer-switched-awaiting-external-observation`: immediately rerun the external
static and application-route probes. Filesystem activation is not evidence that
CDN caches or public traffic observed the new release.

After that switch, rerun `web_release_probe.py` and
`web_application_route_probe.py` into new write-once files; never reuse preview
observations. Bind the pointer execution and these two post-switch files with
`web_release_completion.py record`. Only its
`production-promotion-observed` result proves the configured HTTPS origin served
the candidate bytes, response policy, `/api/health`, and `/ws` after the switch.
It remains a point-in-time observation, not continuous availability or branded-
browser evidence.

## Roll back without rebuilding

Keep the previous release directory throughout the rollback window. To roll
back, activate its existing release ID and run status again:

```bash
python3 tools/web_release_store.py activate \
  --store-root /srv/chat-room-web \
  --release-id <previous-version>-<previous-git-sha>

python3 tools/web_release_store.py status \
  --store-root /srv/chat-room-web
```

Probe the restored HTTPS release again. Filesystem status alone is insufficient
when routing or response-header configuration caused the incident.

Retain separate observations for the previously healthy A, active B, and
restored A. Bind them into one write-once rollback record, then independently
verify the record against all three source files:

Use `web_release_rollback_execution.py execute` for the pointer mutation. It
derives the exact B→A pair from durable promotion-execution evidence, requires B
to be current, and consumes that rollback once. It intentionally needs no fresh
promotion approval: the original authorization already selected A, and delaying
an incident rollback for a new version choice would increase recovery time. If
evidence persistence fails after A is restored, leave A active and investigate;
never automatically return to failed B.

After restoration, rerun both the strict static probe and application-route
probe against production A, then bind those fresh files to the exact rollback
execution with `web_release_rollback_completion.py record`. Only
`production-rollback-observed` closes the incident; the generic three-static-
observation record below remains useful no-rebuild evidence but is insufficient
without restored `/api/health` and `/ws`.

```bash
python3 tools/web_rollback_evidence.py record \
  --prior /path/to/evidence/web-release-A-before.json \
  --current /path/to/evidence/web-release-B.json \
  --restored /path/to/evidence/web-release-A-restored.json \
  --output /path/to/evidence/web-rollback-B-to-A.json

python3 tools/web_rollback_evidence.py verify \
  --prior /path/to/evidence/web-release-A-before.json \
  --current /path/to/evidence/web-release-B.json \
  --restored /path/to/evidence/web-release-A-restored.json \
  --output /path/to/evidence/web-rollback-B-to-A.json
```

The three observation times must be strictly ordered, B must differ from A, and
the restored A must match the prior A artifact manifest, response policy,
identity, and complete observed path set. This evidence is only as authoritative
as the environment named by its HTTPS origin.

Do not copy old bytes over the new directory, rebuild from an old branch, or
reuse the new release's response policy. Stop the rollout if status fails. Any
eventual garbage collection needs a separately reviewed retention policy and
must never remove the active or designated rollback release.
