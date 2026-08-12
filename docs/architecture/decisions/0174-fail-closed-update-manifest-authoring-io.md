# ADR-0174: Fail-Closed Update Manifest Authoring I/O

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and security
- Extends: ADR-0117

## Context

Canonical serialization alone does not reject a source JSON object containing
duplicate keys because a permissive parser can silently retain the last value.
The offline Ed25519 signer also replaced an existing detached-signature path,
allowing a later invocation to mutate evidence that may already have been
reviewed. Both behaviors are unacceptable before introducing production update
key custody.

## Decision

- Parse every update-manifest object with duplicate-key rejection before schema
  validation and exact canonical-byte comparison.
- Keep the detached signature exactly 64 bytes and atomically produced.
- Refuse signing when the output already exists or is a symbolic link, overlaps
  manifest/key input, or has an unsafe parent directory.
- Preserve external-key-only and outside-repository constraints; this ADR does
  not authorize the fixture PEM interface for production automation.

## Consequences

One canonical manifest maps to one write-once signature output in an authoring
attempt, and ambiguous JSON never reaches validation or cryptography. Operators
must choose a fresh evidence directory for a new signing attempt. Production
hardware/protected key access and manifest publication remain unimplemented.

## Migration and Rollback

No public manifest exists. Existing test fixtures regenerate into new temporary
paths. Do not weaken parsing or overwrite behavior to support a failed attempt;
discard its unpublished directory and begin a newly authorized operation.

## Verification

- `python3 Tests/windows_update_manifest_test.py`
- reject root/nested duplicate keys before canonical acceptance
- reject a second signature write while preserving the first 64-byte signature
- retain tamper, expiry, rollout, URL, installer-name, and key-location tests
