# ADR-0176: Self-Contained Windows Update-Channel Candidate

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and security
- Related milestone: M4
- Extends: ADR-0141, ADR-0169, ADR-0175

## Context

The immutable Windows release candidate proves the complete signed application
payload, while the canonical Ed25519 manifest proves what an updater may fetch.
Keeping them as unrelated artifacts leaves room for a valid manifest to name a
different Setup, publisher, version, or source revision. It also makes later
approval depend on transient files from separate protected operations.

## Decision

- Add a provider-neutral assembler/verifier for one unpublished update-channel
  candidate containing the complete schema-5 Windows candidate, canonical
  update manifest, detached 64-byte signature, and reviewed public PEM.
- Require the manifest channel, version, source revision, sequence, key ID,
  installer URL, Setup size/SHA-256, and Authenticode publisher thumbprint to
  match the exact inner Windows candidate.
- Bind the reviewed public PEM by caller-supplied SHA-256 and bind the inner
  candidate manifest by SHA-256. Copy no private key or signing credential.
- Close and sort the complete file inventory and `SHA256SUMS`; reject links,
  undeclared files, changed bytes, an existing destination, and unsafe paths.
- Validate all live inputs at assembly time, record a whole-second UTC
  `assembledAt`, then replay inner signature/freshness checks against that
  instant for durable later audit. Reject candidates assembled in the
  verifier's future.
- Assemble through a sibling temporary directory and atomically rename into a
  previously absent destination.
- Mark the result `signed-update-channel-not-published-candidate`. Assembly
  neither uploads Setup nor changes stable/beta channel state.

## Consequences

A later approval or publication workflow can consume one closed artifact and
prove that the update metadata authorizes the exact Windows bytes already
accepted by the protected Authenticode path. The public key is distributable
verification material, but including it here does not provision client trust.
The candidate is intentionally larger because it retains the complete release
and its evidence.

## Migration and Rollback

No existing Windows or update channel is mutated. A failed assembly removes its
temporary directory; a rejected or obsolete candidate is discarded rather than
edited. Removing the assembler restores the prior two-artifact process until a
publication consumer depends on this contract.

## Verification

- `python3 Tests/windows_update_channel_candidate_test.py`
- assemble and independently verify a complete fixture with no private key;
- remain verifiable after manifest expiry by replaying the immutable assembly
  instant;
- reject signature/byte/identity tampering, undeclared private material, and a
  validly re-signed manifest naming different Setup metadata;
- require real protected PKCS#11 signing, reviewed product public-key
  provisioning, and Windows release execution before any publication claim.
