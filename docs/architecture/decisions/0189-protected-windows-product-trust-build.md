# ADR-0189: Protected Windows Product-Trust Build

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and security
- Related milestone: M4
- Extends: ADR-0159, ADR-0160, ADR-0185, ADR-0188

## Context

Ordinary native CI deliberately compiles Windows updates off and emits a
schema-4 artifact with `productUpdateTrust: null`. The product-trust intent and
final-PE evidence define what a release-capable client must contain, but no
workflow yet builds that client without allowing arbitrary runtime drift or
mixing public trust provisioning with Authenticode keys and publication.

## Decision

- Add a manual workflow bound to the `windows-update-product-trust`
  environment and a dedicated self-hosted Windows/x64 runner class.
- Accept only public reviewed policy: exact revision, ordinary artifact run,
  stable/beta channel, credential-free HTTPS manifest URL, key IDs, and public
  PEM file SHA-256 values. Public PEM paths and libsodium are runner
  configuration; private keys, credentials, signing, and publication are absent.
- Download and independently require the exact ordinary schema-4/null-trust
  artifact. Use it as the runtime baseline rather than rebuilding or
  redeploying dependencies.
- Configure the canonical CMake client from one fresh ADR-0185 intent and
  replace only `ChatClient.exe`. Compare the resulting directory with the
  ordinary CMake/default-off payload: every runtime and update-helper byte must
  remain identical, while only the client PE may differ.
- Execute the final PE diagnostic and create ADR-0187 evidence before
  packaging. Build an explicitly unsigned NSIS installer, install it silently,
  require the installed diagnostic to equal the built PE diagnostic, then
  require complete uninstall cleanup.
- Emit one seven-day, unpublished schema-4 artifact that closes the intent,
  diagnostic, binary evidence, reviewed public PEM files, parity evidence,
  exact payload, and unsigned installer. Independently verify it with
  `--require-product-update-trust` before upload.
- Keep protected Authenticode as the next distinct boundary. It must consume
  this artifact rather than an ordinary null-trust artifact.

## Consequences

Product update trust becomes a reproducible, reviewed, native-Windows artifact
instead of an inferred CMake option. Ordinary CI remains default-deny. The
workflow cannot sign, publish, or mutate stable/beta delivery, and the
repository contains no positive run evidence until the dedicated runner and
environment execute it successfully.

## Migration and Rollback

Protected signing continues to consume its existing artifact until it is
explicitly migrated to require this new artifact. Rollback disables or removes
the manual trust-build workflow; ordinary null-trust CI remains unchanged.
Never bypass the trust-required verifier by relabelling an ordinary artifact.

## Verification

- `python3 Tests/windows_product_trust_build_workflow_test.py`
- `python3 Tests/windows_client_payload_parity_test.py`
- `python3 Tests/windows_artifact_manifest_test.py`
- `python3 Tests/windows_unsigned_artifact_verifier_test.py`
- `python3 Tests/windows_update_product_trust_evidence_test.py`
- native protected execution must still be recorded before claiming a
  trust-enabled Windows release candidate.
