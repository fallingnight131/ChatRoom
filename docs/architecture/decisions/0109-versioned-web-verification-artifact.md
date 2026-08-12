# ADR-0109: Versioned Web Verification Artifact

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The Web gate performs a clean dependency install, tests, and a Vite production
build. Its `dist` tree was previously ephemeral and had no machine-readable
identity, cache classification, source revision, or explicit source-map guard.
Those are prerequisites for immutable deployment and rollback, but they do not
by themselves prove that any hosting environment applied security headers or
served the expected bytes.

Windows and Web clients also need independent release cadence. ADR-0108's root
`VERSION` remains the Windows desktop version; Web already has a lockfile-owned
package version.

## Decision

- Treat matching `WebClient/package.json`, top-level lockfile, and locked root
  package versions as the canonical Web release version. Require canonical
  SemVer and keep it independent from the Windows root `VERSION`.
- After the default production build, copy `dist` into a short-lived `site/`
  verification payload and generate deterministic `web-artifact-manifest.json`
  plus `SHA256SUMS` metadata tied to the exact Git revision.
- Require `index.html`, at least one local content-hashed JavaScript entrypoint,
  existing local link/script targets, hashed names for every `assets/` file,
  regular files only, and stable bytes while hashing.
- Reject inline/external entrypoint scripts, query/fragment asset references,
  source-map files, trailing browser-consumed source-map directives, unhashed
  assets, missing references, invalid package metadata, symlinks, and unsafe
  paths. Do not mistake dependency runtime strings that can generate their own
  CSS diagnostics for a published JavaScript map trailer.
- Classify `index.html` as `no-store`, hashed `assets/` as
  `public,max-age=31536000,immutable`, and any other root static file as
  `no-cache`. The future deployer must apply and verify these exact policies.
- Label the artifact `unsigned-not-deployed-verification-only`. Do not call it a
  deployment, release rollout, CSP result, supported-browser result, or rollback
  result.

## Consequences

CI retains a source-traceable Web tree whose immutable/cache assumptions can be
verified before hosting work begins. Accidental public source-map output,
external bootstrap dependencies, and unhashed asset regressions now fail early.

This slice does not choose a hosting provider, publish a URL, pin a browser
matrix, apply CSP/HSTS, perform a health check, move a deployment pointer, or
prove rollback. Those remain required M4 work. SHA-256 metadata detects change
but does not authenticate a publisher or hosting response.

## Migration and Rollback

Runtime routes, V1/V2 behavior, IndexedDB, server endpoints, and the default-off
preview are unchanged. Rollback removes the post-build validator/artifact steps;
normal Vite builds remain available. Existing verification artifacts are
short-lived and explicitly not deployed.

## Verification

- Python tests cover deterministic metadata/cache classification, package-lock
  drift, external/inline/missing/unhashed entrypoints, source-map files/trailing
  directives, and unhashed payload assets;
- the full Web gate performs `npm ci`, all tests, and the production build before
  validating the real Vite tree;
- CI uploads the `site/`, manifest, and checksums under an artifact name that
  includes Web version, not-deployed status, and Git revision;
- public Web compatibility and deployment remain unclaimed until isolated
  deployment, header, health, rollout, and rollback acceptance passes.
