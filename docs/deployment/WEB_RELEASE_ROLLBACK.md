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

Do not copy old bytes over the new directory, rebuild from an old branch, or
reuse the new release's response policy. Stop the rollout if status fails. Any
eventual garbage collection needs a separately reviewed retention policy and
must never remove the active or designated rollback release.
