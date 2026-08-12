# ADR-0177: Protected Windows Update-Candidate Workflow

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and security
- Related milestone: M4
- Extends: ADR-0165, ADR-0175, ADR-0176

## Context

The repository has a hardware-backed update signing primitive and a closed
update-channel candidate format, but no orchestrated boundary joins them. Using
the Authenticode runner for both keys would couple two trust domains, while
allowing ordinary CI to author a production signature would expose excessive
authority. The operation must also consume the exact previously accepted
Windows candidate rather than loose Setup bytes.

## Decision

- Add a manual workflow in the protected `windows-update-production-signing`
  environment on the dedicated
  `self-hosted-windows-update-signing` Windows x64 runner class.
- Grant only repository `contents: read` and `actions: read`. Check out the exact
  candidate source revision without persisted credentials.
- Download exactly one deterministically named artifact from an explicit prior
  protected Windows signing run and independently verify its schema-5 identity
  before authoring update metadata.
- Accept only public release policy inputs: channel, manifest sequence, key ID,
  reviewed public-key digest, minimum updatable version, rollout percentage and
  seed, installer URL, and reviewed Authenticode publisher digest. Validate
  bounded forms before use.
- Keep the credential-free PKCS#11 object URI and reviewed public PEM path in
  protected runner configuration. Accept no private-key file, PIN, password,
  provider installer, or secret workflow input.
- Author a seven-day canonical manifest for the exact candidate Setup, sign it
  through ADR-0175, independently verify it, then assemble and verify the
  ADR-0176 closed candidate.
- Upload exactly one seven-day evidence artifact marked
  `signed-update-not-published`. Do not create a GitHub Release, upload to the
  update origin, provision client trust, or mutate stable/beta state.

## Consequences

Authenticode and Ed25519 keys remain operationally separated, and the latter
can authorize only a release already accepted by the former workflow. Manual
inputs still require external review, especially monotonic sequence, endpoint,
rollout, and public-key identity. A successful static policy test is not proof
that the protected runner, HSM, public key, or Windows signatures exist.

## Migration and Rollback

The workflow is manual and non-publishing, so disabling it stops new candidates
without changing users' channels. Reject or let a seven-day artifact expire;
never edit it. Any replacement requires a fresh run, new manifest sequence when
appropriate, and a new closed candidate.

## Verification

- `python3 Tests/windows_protected_update_workflow_test.py`
- `python3 Tests/windows_update_protected_signer_policy_test.py`
- `python3 Tests/windows_update_channel_candidate_test.py`
- parse both workflow YAML files;
- require a real approved protected-runner execution before claiming positive
  Ed25519, HSM, Authenticode, installer, or update-channel evidence.
