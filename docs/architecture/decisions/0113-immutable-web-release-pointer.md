# ADR-0113: Immutable Web Releases and Atomic Activation Pointer

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0109 creates a versioned Web artifact, ADR-0111 fixes its production network
boundary, and ADR-0112 binds its response policy. Fast rollback still cannot be
claimed if deployment overwrites a shared `dist` directory or rebuilds an older
Git revision during an incident. The release needs immutable version storage,
one small activation boundary, integrity-aware health output, and an isolated
upgrade/rollback rehearsal before a provider-specific adapter is selected.

## Decision

- Store each complete artifact under
  `releases/<semver>-<40-character-source-revision>`. A release contains its site,
  bound response policy, manifest, and checksums; no undeclared file is accepted.
- Validate schema, identity, paths, exact file set, sizes, SHA-256 values, and
  checksums before copying; validate again in a sibling staging directory before
  an atomic rename into the release store.
- Treat release directories as immutable. Re-staging identical content is
  idempotent. An existing identity that fails validation is a hard error, not an
  overwrite request.
- Select one release through atomically replaced `active-release.json`. The
  pointer records release ID, version, source revision, entrypoint, policy digest,
  file count, and activation time.
- Define filesystem health as rereading every selected byte and matching every
  pointer identity field. This is pre-provider health; production promotion also
  requires HTTPS response-header, `/ws`, `/api/`, monitoring, and browser checks.
- Roll back by selecting the retained previous release. Never rebuild or combine
  an old site with a new response policy during an incident.
- Defer deletion/retention automation until a release-owner and rollback-window
  policy exist. Garbage collection must not be inferred from staging.

## Consequences

Upgrade and rollback mechanics are deterministic, provider-neutral, and tested
without network or cloud credentials. A future Nginx, object-storage/CDN, or
other adapter can translate the pointer boundary to its own atomic routing
primitive while preserving the same release identity and policy coupling.

This does not publish the application, observe live security headers, prove TLS,
define staged traffic percentages, or establish browser compatibility. The
artifact's historical `unsigned-not-deployed-verification-only` status therefore
remains accurate in CI.

## Migration and Rollback

There is no application protocol or data migration. Seed the store with the
current artifact, activate it only in an isolated environment, then stage a
second artifact and rehearse both forward activation and rollback. Removing the
tool leaves existing artifacts unchanged; a hosting adapter can restore its
previous routing configuration.

## Verification

- tests stage two distinct versions, activate A, upgrade to B, roll back to the
  original A bytes, and keep B available;
- repeated staging is idempotent;
- tampered payloads, undeclared files, missing releases, unsafe paths, checksum
  drift, and pointer/release identity mismatch fail closed;
- CI runs the release-store policy tests before assembling the Web artifact.
