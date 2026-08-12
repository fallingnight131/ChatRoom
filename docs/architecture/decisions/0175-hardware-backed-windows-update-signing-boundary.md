# ADR-0175: Hardware-Backed Windows Update Signing Boundary

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and security
- Extends: ADR-0117, ADR-0174

## Context

The offline manifest tool accepts an external PEM private-key path to exercise
Ed25519 behavior in tests. Passing that interface into CI would expose
exportable key material and encourage storage in runner files or secret values.
The update key must remain independent from Authenticode and its operation must
not silently publish a channel.

## Decision

- Add a production signing adapter that accepts no private-key file or bytes.
- Require a credential-free PKCS#11 object URI from protected runner
  configuration. Reject query/fragment, whitespace, PIN source/value, password,
  or secret material in the URI.
- Require preinstalled OpenSSL 3 and a separately provisioned reviewed PKCS#11
  provider; install no dependencies during protected execution.
- Keep HSM authentication and PIN delivery entirely outside workflow inputs,
  environment secrets, command arguments, repository, logs, and artifacts. The
  dedicated runner service/provider owns the authenticated session.
- Require the canonical manifest's signing key ID to match the approved key ID.
- Require a regular non-reparse public PEM whose exact file SHA-256 is a reviewed
  public input.
- Sign through the PKCS#11 URI, require exactly 64 bytes, immediately verify
  against the public PEM, then atomically create a previously absent signature.
- Do not upload, publish, or mutate stable/beta state in this primitive.

## Consequences

Production key material can remain non-exportable while the repository retains
a provider-neutral OpenSSL boundary and independent public verification. Runner
provisioning and HSM availability are external dependencies. Static policy tests
do not prove a real provider, key, or authenticated signing operation exists.

## Migration and Rollback

Keep the PEM path signer only for isolated cryptographic fixtures and offline
development. Production workflow policy must invoke only the protected adapter.
On any failure, emit no signature and create no channel state; reset the runner
session and require a fresh authorized operation.

## Verification

- `python3 Tests/windows_update_protected_signer_policy_test.py`
- `python3 Tests/windows_update_manifest_test.py`
- reject private-key/PFX/PIN/password/secret/provider-install inputs
- require canonical manifest inspection, exact key ID/public-key digest,
  OpenSSL 3, 64-byte signature, immediate public verification, and write-once
  publication
- require a real protected PKCS#11 runner execution before any positive signing
  or channel claim
