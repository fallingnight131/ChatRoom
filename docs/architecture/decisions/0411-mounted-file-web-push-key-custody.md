# ADR-0411: Mounted-File Web Push Key Custody

- Status: Accepted
- Date: 2026-08-17
- Owners: project maintainers
- Related milestone: M6
- Extends: ADR-0409

## Context

Web Push subscription endpoints and browser authentication material are stored
under AES-256-GCM, with endpoint ownership indexed by a separate HMAC-SHA256
lookup key. The existing crypto adapter accepts a custody port but only tests it
with fixture memory. Supplying raw keys in environment values would leave
immutable secret strings in process and launcher state, and accepting one
unversioned encryption key would make safe rotation impossible.

## Decision

- Load raw 32-byte Web Push keys only from externally provisioned mounted files.
  Repository files, command-line values, and environment secret values are not
  accepted. Runtime configuration will carry only paths and non-secret key IDs.
- Require a regular non-symbolic-link file, exact 32-byte content, owner read
  permission, and no group/other permission. Reject filesystems without POSIX
  permission enforcement. The backend deployment target for this adapter is a
  POSIX server host; this does not alter the Web/Windows client product scope.
- Keep one named active encryption key and a bounded named ring of prior
  encryption keys. New ciphertext uses only the active key ID; old ciphertext
  resolves the recorded key ID during a reviewed rotation window.
- Keep the endpoint lookup key cryptographically distinct from every encryption
  key. Reusing bytes between key IDs or purposes fails startup.
- Copy key material into clearable process-owned storage at startup. Every
  operation receives a short-lived copy that is zeroed after its callback;
  shutdown clears the owned values. Unknown key IDs and use after close fail
  without exposing key bytes.
- External secret provisioning, backup, restore, and access audit remain the
  deployment system's responsibility. Lookup-key rotation still requires a
  separately implemented transactional endpoint-tag rewrite before activation.

## Consequences

The process can decrypt old rows while encrypting with a new key without
placing secrets in repository or environment values. Rotation is restart-based
for now, and overly broad permissions fail startup. Kubernetes/Docker secret
mount defaults must be hardened to the documented POSIX policy rather than
silently accepted.

This adapter alone does not compose subscription writes, expose an HTTP route,
activate outbox production, or prove backup/restore and lookup-key rotation.

## Verification and rollback

- Unit tests use protected temporary files and prove active/old/lookup access,
  callback-copy zeroing, close behavior, invalid/missing IDs, duplicate-purpose
  refusal, exact length, permission rejection, and symbolic-link rejection.
- The existing AES-GCM/HMAC suite continues to prove cryptographic shape and
  old-key resolution through the custody port.
- Rollback removes runtime references before removing this additive adapter.
  Existing ciphertext and key files remain untouched.
