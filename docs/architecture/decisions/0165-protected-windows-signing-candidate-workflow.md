# ADR-0165: Protected Windows Signing Candidate Workflow

- Status: Accepted; four-subject flow amended by ADR-0167
- Date: 2026-08-12
- Owners: Release engineering and security

## Context

ADR-0161 through ADR-0164 define a closed unsigned artifact, a fresh protected
signing intent, a candidate that retains that intent, and an explicit release
installer identity. They do not define where private-key operations may run.
Rebuilding the C++ payload on a signing host would create new unverified bytes,
while accepting unvalidated dispatch strings in shell blocks would introduce a
command-injection boundary. Combining signing with publication would also make
key custody, candidate acceptance, and release authorization one irreversible
operation.

## Decision

Add a manual GitHub Actions workflow that:

- grants only `actions: read` and `contents: read`;
- requires the protected `windows-production-signing` environment and dedicated
  `self-hosted-windows-signing` Windows/x64 runner, with signing jobs serialized;
- checks out the exact reviewed commit without persistent Git credentials;
- passes dispatch values into shell blocks only through environment variables,
  then validates closed revision, run, channel, signer, timestamp, and version
  identities;
- creates and verifies the fresh protected-signing intent, downloads the exact
  ordinary-CI artifact, reruns closed unsigned-artifact verification, and
  requires the client, helper, and verification Setup to be `NotSigned`;
- neither rebuilds the C++ payload nor installs dependencies, and requires the
  runner's preinstalled NSIS version to be exactly 3.12;
- selects exactly one certificate from `Cert:\LocalMachine\My` by SHA-1,
  requires its private key, code-signing EKU, current validity, and exact SHA-256
  certificate identity, and accepts no PFX or password input;
- invokes preinstalled `signtool.exe` with SHA-256 file and timestamp digests and
  a reviewed credential-free HTTPS RFC 3161 endpoint;
- signs the client and helper, compiles release-mode Setup around those final
  payload bytes, and signs Setup as separate visible operations;
- generates provider-neutral signature evidence, independently verifies it,
  assembles the schema-2 candidate with the protected intent, and independently
  verifies the complete candidate; and
- uploads exactly one seven-day `signed-not-published` workflow artifact.

The workflow must not create a GitHub Release, publish or sign an update
manifest, contact a release endpoint, or promote a stable/beta channel.

## Consequences

Private-key custody is isolated from ordinary CI and the exact unsigned bytes
remain independently traceable into the candidate. Structured evidence can be
reviewed without granting publication authority. The workflow depends on an
externally provisioned protected environment, clean runner, machine-store
certificate, Windows SDK, and pinned NSIS. Repository policy tests prove only
the workflow's static boundary; they are not positive native signing evidence.

## Migration and Rollback

Provision environment protection, runner hardening, tools, and certificate
access out of band. Rehearse candidate-only runs before defining publication.
If validation or signing fails, retain no release state, destroy or reset the
runner, and investigate the unpublished short-lived artifact. Publication and
update-channel mutation require a later independent ADR and workflow boundary.

## Verification

- `python3 Tests/windows_protected_signing_workflow_test.py`
- parse all repository workflow YAML files
- run the existing unsigned-artifact, intent, release-evidence, candidate,
  signature-policy, and release-installer-mode mutation suites
- on the protected Windows runner, require a successful real candidate before
  claiming signed Windows evidence
