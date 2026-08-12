# ADR-0140: Independent Windows Release Evidence Verification

- Status: Accepted; four-subject verification amended by ADR-0167
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4
- Extends: ADR-0139

## Context

ADR-0139 asks Windows for Authenticode status and records final file hashes. A
single PowerShell step could still be given one set of paths while a later
publish step uploads another, or its JSON evidence could be stale, extended, or
modified. Release acceptance needs an independent implementation that binds the
evidence back to the exact candidate bytes before publication.

## Decision

- Add a cross-platform Python verifier for the closed schema-1 signature
  evidence. Require exact root and artifact keys, product, numeric version,
  lowercase 40-hex Git revision, `x86_64`, reviewed signer SHA-256, and the exact
  ordered roles/names for client, update helper, and final Setup.
- Require a whole-second UTC observation no more than 24 hours old and no more
  than five minutes in the future. Refuse evidence or candidate symlinks.
- Recompute each final candidate's size and SHA-256 independently. Require every
  entry to retain the expected publisher SHA-256, a lowercase timestamp-
  certificate SHA-256, and the exact `valid-timestamped-authenticode` outcome.
- Run mutation tests in baseline CI for unknown fields, identity/time mismatch,
  changed bytes, wrong publisher, missing timestamp, and reordered roles.
- A future protected release job must run ADR-0139 on Windows and then this
  verifier against the same immutable candidate paths before upload, manifest
  signing, promotion, or channel publication.
- Do not treat constructed test JSON as evidence of an actual Windows signature.

## Consequences

The publisher-facing release gate now has two independent responsibilities:
Windows establishes platform signature trust, while Python establishes closed
evidence semantics and final-byte identity. Neither accepts private signing
material. A release still needs protected credentials, positive native output,
install/upgrade verification, and channel rollback rehearsal.

## Migration and Rollback

No production data or secret change. Rolling back this verifier weakens future
publication safety and therefore requires a replacement independent final-byte
check plus ADR once a protected release workflow depends on it.

## Verification

- exact fresh fixture evidence matching three files passes;
- unknown fields, wrong revision, stale evidence, changed bytes, wrong signer,
  missing timestamp, and role reordering fail;
- baseline CI runs both the PowerShell source policy and Python evidence tests;
- no positive Authenticode or Windows support claim is made from fixtures.
