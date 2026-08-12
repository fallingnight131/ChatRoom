# ADR-0167: Four-Subject Windows Signing Candidate

- Status: Accepted; candidate schema advanced by ADR-0168
- Date: 2026-08-12
- Owners: Release engineering and security
- Extends: ADR-0139, ADR-0140, ADR-0141, ADR-0163, ADR-0165, ADR-0166

## Context

ADR-0166 makes the NSIS-generated uninstaller available for external signing,
but the protected workflow and immutable evidence still covered only the client,
update helper, and outer Setup. Signing an uninstaller without retaining and
independently verifying its exact bytes would leave a gap between key use,
installer compilation, and the later release candidate.

## Decision

- Add `Uninstaller` as an exclusive mode of the machine-store signing tool and
  require the canonical `ChatRoom-<version>-Uninstall.exe` identity.
- In the protected workflow, compile export mode from the already signed client
  payload, explicitly sign/timestamp the exported uninstaller, compile import
  mode from those bytes, remove the non-release helper, and explicitly
  sign/timestamp Setup.
- Advance provider-neutral Windows signature evidence to schema 2 with the fixed
  role order client, update launcher, uninstaller, installer.
- Require all four subjects to have valid timestamped Authenticode, the same
  reviewed publisher-certificate SHA-256, closed names, and final-byte hashes.
- Advance the immutable Windows candidate to schema 3. Retain the standalone
  signed uninstaller under `installer/`, declare `uninstallerPath`, include it in
  the sorted file list and checksums, and independently rerun evidence validation
  after copying.
- Keep the workflow candidate-only. It still cannot publish a release, sign an
  update manifest, or mutate a channel.

## Consequences

Every project-owned executable involved in install and uninstall has a closed
signing/evidence identity, and candidate verification can detect a substituted
uninstaller without unpacking Setup. Candidate size increases by one retained
PE. Repository and macOS checks still cannot prove Authenticode or that the
installed uninstaller bytes match the retained standalone file.

## Migration and Rollback

Schemas 1 and 2 of their respective pre-release evidence/candidate formats are
rejected rather than silently reinterpreted. No public release consumed them.
Rollback returns to the unsigned verification workflow and must not publish an
older candidate schema. A later native acceptance step must install Setup and
compare the installed uninstaller's hash and signature before publication.

## Verification

- `python3 Tests/windows_release_signature_policy_test.py`
- `python3 Tests/windows_release_evidence_test.py`
- `python3 Tests/windows_release_candidate_test.py`
- `python3 Tests/windows_protected_signing_workflow_test.py`
- parse all workflow YAML
- execute the protected workflow on its native runner before claiming positive
  signature or installed-byte evidence
