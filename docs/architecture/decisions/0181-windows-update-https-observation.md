# ADR-0181: Windows Update HTTPS Observation

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and operations
- Related milestone: M4
- Extends: ADR-0180

## Context

An atomic provider pointer can succeed while TLS, CDN caching, routing, or file
deployment still serves users stale, partial, compressed, or redirected bytes.
Local pointer evidence therefore cannot complete an update-channel promotion.
The manifest, detached signature, and Setup must be observed as one exact public
release identity.

## Decision

- Add a provider-neutral HTTPS probe for the exact canonical `manifest.json`,
  adjacent `manifest.json.sig`, and signed Setup URL from a closed candidate.
- Require credential-free HTTPS, one origin and directory, no query, fragment,
  encoded path, redirect, response URL change, content encoding, cookie, or CORS
  header. Require TLS 1.2 or newer with normal hostname/chain verification.
- Require exact `Content-Length`, deliberate content types, HSTS, `nosniff`,
  `no-store` for manifest/signature, and one-year immutable caching for Setup.
- Read only the expected byte bound and require byte-for-byte equality with the
  candidate for all three resources.
- Revalidate the candidate and Ed25519 signature at the observation instant;
  bind channel, version, revision, sequence, key ID, URLs, three SHA-256 values,
  Setup size, Authenticode publisher identity, and exact UTC observation time.
- Persist closed observation evidence once. The probe has no mutation or
  provider credential.

## Consequences

Operators can distinguish local activation from what a client would fetch over
the public trust path. Strict headers require deliberate update-origin policy
and prevent intermediary transformations. A single successful observation is
point-in-time evidence, not an uptime or global CDN consistency claim.

## Migration and Rollback

No channel state changes. Origins must adopt the documented response policy
before completion can pass. A failed or stale observation is replaced by a new
write-once record after remediation; recorded evidence is never overwritten.

## Verification

- `python3 Tests/windows_update_release_probe_test.py`
- isolated trusted TLS success plus rejection for untrusted TLS, redirects,
  wrong bytes, missing security headers, cross-directory URLs, duplicate fields,
  mutation, and evidence overwrite;
- production origin observation remains a release-time gate.
