# ADR-0163: Bind Signing Intent into Windows Candidate

- Status: Accepted; candidate schema advanced by ADR-0167
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0162 creates a precise protected-signing intent, while candidate schema 1
retained only signature evidence. Uploading the intent beside the candidate
would not make it immutable with the signed bytes and would permit accidental
loss or mismatch during handoff.

Hashing intent bytes alone is insufficient if an attacker can rewrite the
intent and recompute a candidate manifest before independent verification. Its
semantics must also be revalidated against the requested candidate identity.

## Decision

- Require a protected-signing intent input when assembling a Windows candidate.
- Verify the intent against candidate version, revision, channel, expected
  signer SHA-256, freshness, environment, runner class, and artifact identity
  before copying any output.
- Copy it to `evidence/protected-signing-intent.json`.
- Advance the candidate manifest to schema 2 and declare the intent path through
  the required `protectedSigningIntentPath` field.
- Include intent size/SHA-256 in the sorted candidate file list and
  `SHA256SUMS`.
- During candidate verification, require exact file closure and bytes, then
  independently rerun semantic intent verification.
- Keep candidate status `signed-timestamped-not-published-candidate`.

## Consequences

The immutable candidate now carries the exact environment-approved intent for
its signed bytes. Independent verification detects both byte tampering and a
semantically rewritten intent even if surrounding hashes were recomputed.

Existing schema-1 candidates no longer validate under the current tool. They
were explicitly unpublished verification structures; no public compatibility
contract is affected.

## Migration and Rollback

The future protected workflow must pass the just-created intent into candidate
assembly. Rollback means producing no new signed candidate; do not downgrade the
verifier to accept missing intent.

## Verification

- assemble and independently validate schema-2 candidate with retained intent;
- require the intent path in exact file closure and checksums;
- mutate intent environment and recompute candidate manifest/checksum, then
  require semantic rejection;
- retain runtime, signature-evidence, file-tamper, missing, extra, and symlink
  candidate tests.
