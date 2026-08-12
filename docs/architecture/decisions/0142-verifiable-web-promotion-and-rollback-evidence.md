# ADR-0142: Verifiable Web Promotion and Rollback Evidence

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0114 proves that one HTTPS observation matches one immutable Web release,
but its JSON output could be overwritten and had no strict reader. A successful
request therefore was not yet a durable release record. The rollback runbook
also did not cryptographically bind the prior healthy release, the replacement,
and the restored release. A local A-to-B-to-A pointer test alone cannot prove
that the externally served origin returned to the exact prior bytes and policy.

## Decision

- Give every HTTPS observation a closed schema and bind the exact artifact
  manifest digest in addition to release ID, source revision, response policy,
  observed paths, origin, and observation time.
- Publish observation files atomically and refuse to overwrite an existing
  record. Evidence contains no credentials, response bodies, private keys, or
  provider configuration.
- Add an independent reader that rejects unknown fields, malformed identity,
  unsorted or duplicate paths, invalid times, digest changes, or disagreement
  with the immutable release directory.
- Permit a recorded observation to be reverified by performing the complete
  HTTPS byte/header probe again against the same exact origin and immutable
  release. A different CLI origin is rejected rather than silently followed.
- Define rollback evidence as the SHA-256 binding of three closed observations:
  prior release A, current release B, and restored release A. Require one HTTPS
  origin, different A/B identities, exact prior/restored artifact and policy
  identity, and strictly increasing prior/current/restored times.
- Write rollback evidence once and independently reconstruct it from the three
  observation records during verification.

## Consequences

Release operators can retain immutable, tamper-evident evidence for promotion
and no-rebuild rollback. Reverification detects later routing, header, TLS, or
byte drift. The tools remain provider-neutral and do not publish traffic,
configure DNS/CDN rules, verify `/ws` or `/api/`, or prove branded-browser
support. Fixture evidence remains explicitly non-production evidence.

## Migration and Rollback

Existing ad hoc probe JSON lacks `evidenceType` and `artifactManifestSha256` and
is intentionally rejected; run the probe again to create a schema-1 observation.
Removing these tools changes no hosted bytes. Keep the three source observation
files for as long as their rollback evidence is retained.

## Verification

- trusted localhost HTTPS verifies exact bytes/headers, write-once persistence,
  strict reading, and independent live reverification;
- mutations, unknown fields, and output replacement fail closed;
- rollback tests cover A-to-B-to-A binding, source-record hash changes,
  different origins, changed restored identity, invalid order, and unknown
  rollback fields.
