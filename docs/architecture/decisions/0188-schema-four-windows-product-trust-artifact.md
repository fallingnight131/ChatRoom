# ADR-0188: Schema-Four Windows Product-Trust Artifact

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and security
- Related milestone: M4
- Extends: ADR-0159, ADR-0161, ADR-0187

## Context

Artifact schema 3 closes the CMake payload and installer but cannot distinguish
a default-off client from one containing reviewed product update trust. Signing
the former would produce a valid Windows release that cannot update; accepting
loose trust evidence beside the artifact would permit substitution.

## Decision

- Advance every unsigned Windows artifact to schema 4 with an exact
  `productUpdateTrust` field.
- Ordinary CI records `null` and remains valid only for verification/testing.
- A trust-enabled artifact records channel, manifest URL, key IDs, and closed
  file metadata for the ADR-0185 intent, ADR-0186 diagnostic, ADR-0187 evidence,
  primary public PEM, and optional secondary public PEM.
- Independently reconstruct the trust bundle against the exact artifact
  `client/ChatClient.exe`; include every trust file in `SHA256SUMS` and the
  artifact's complete file closure.
- Add `--require-product-update-trust` to signing intake. When set, reject
  schema 3, schema-4 `null`, incomplete metadata/files, mismatch, mutation, or
  unverified compiled trust.
- Do not add private material. PEM files are public verification inputs.

## Consequences

Ordinary and release-intended unsigned artifacts have an explicit, machine-
verified distinction. Protected Authenticode can require a release-capable
client without weakening default-off CI. Schema 3 artifacts remain historical
evidence but are no longer accepted by the current intake verifier.

## Migration and Rollback

Ordinary build generation moves immediately to schema 4/null. A dedicated
trust-enabled build must provide the full bundle before protected signing is
changed to require it. Rollback may temporarily restore schema 3 tools only
before any schema-4-required signing run; never silently reinterpret old files.

## Verification

- `python3 Tests/windows_artifact_manifest_test.py`
- `python3 Tests/windows_unsigned_artifact_verifier_test.py`
- `python3 Tests/windows_update_product_trust_evidence_test.py`
- reject null trust when required and all missing/changed trust inputs.
