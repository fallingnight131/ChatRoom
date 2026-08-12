# ADR-0168: Native Windows Install Acceptance Evidence

- Status: Accepted; durable candidate verification amended by ADR-0169
- Date: 2026-08-12
- Owners: Release engineering and security
- Extends: ADR-0141, ADR-0165, ADR-0167

## Context

Four valid signatures and a two-pass NSIS build do not alone prove that Setup
installs the expected bytes, registers the intended product identity, or leaves
a trustworthy uninstaller that succeeds. Logs on a protected runner are also
too weak unless the result is closed, independently verifiable, and retained in
the immutable candidate.

## Decision

- Require native acceptance on the same protected Windows runner after all four
  subjects have valid signature evidence and before candidate assembly.
- Require a previously absent, absolute, space-free install path and no existing
  Chat Room uninstall registration. Refuse reparse-point boundaries.
- Run signed Setup silently and require exit code zero.
- Require installed `ChatClient.exe`, `ChatRoomUpdateLauncher.exe`, and
  `Uninstall.exe` to be regular files with exact size/SHA-256 equality to their
  signed source subjects.
- Require all three installed executables to retain valid timestamped
  Authenticode from the reviewed publisher certificate.
- Require uninstall registration to match version, source revision, and install
  location; run the installed signed uninstaller silently and require it to
  remove the program directory, staging/backup siblings, and registration.
- Write schema-1 evidence only after every check succeeds. Independently verify
  its closed shape, freshness, success booleans, role order, and source/installed
  hashes against the final four source files.
- Advance the candidate to schema 4 and retain the acceptance evidence under
  `evidence/windows-install-acceptance.json`.
- Do not erase a failed install boundary automatically; fail the job and reset
  or destroy the dedicated runner so unexpected files are not executed again.

## Consequences

An accepted candidate carries reviewable proof that its signed installer and
uninstaller completed one clean native lifecycle and that installed executable
bytes matched the retained sources. This is stronger than compile or fixture
evidence, but one runner execution is not yet the Windows 10/11 clean-host
support matrix, upgrade compatibility, or update-channel rollback proof.

## Migration and Rollback

Candidate schema 3 is rejected rather than reinterpreted. No public release has
consumed it. Rollback disables the protected candidate workflow and returns to
unsigned verification artifacts; it must not publish a candidate without native
acceptance evidence.

## Verification

- `python3 Tests/windows_signed_install_policy_test.py`
- `python3 Tests/windows_install_evidence_test.py`
- `python3 Tests/windows_release_candidate_test.py`
- `python3 Tests/windows_protected_signing_workflow_test.py`
- real protected Windows execution before any positive release claim
