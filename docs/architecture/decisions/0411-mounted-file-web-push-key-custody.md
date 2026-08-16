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
  deployment system's responsibility. Encryption- and lookup-key rotation uses
  an offline, whole-table PostgreSQL rewrite before the target keys activate:
  the gateway is stopped, the table is held under `ACCESS EXCLUSIVE`, every
  credential is authenticated with the source custody and protected with the
  target custody, and one transaction commits all rows or none.
- Runtime enables subscription mutation only when the WSS credential issuer and
  the independent subscription gate are exactly true. Configuration supplies a
  protected directory, one active ID, and a comma-separated ring of at most
  eight IDs; files use fixed `encryption-<id>.key` and `endpoint-lookup.key`
  names. Custody closes after the product listener and bounded workers stop.

## Consequences

The process can decrypt old rows while encrypting with a new key without
placing secrets in repository or environment values. The offline rewrite is
bounded by an operator-supplied row ceiling of at most one million and fetches
at most one thousand rows per JDBC page, but deliberately holds one database
transaction and blocks subscription access for correctness. It is therefore a
maintenance operation, not an online/background migration. Overly broad
permissions fail startup. Kubernetes/Docker secret mount defaults must be
hardened to the documented POSIX policy rather than silently accepted.

The runtime can now compose subscription writes and the HTTP route under both
exact gates. This does not activate a default Web client, outbox production, or
provider delivery. The rewrite primitive does not itself stop the gateway,
provision/delete keys, take a database backup, or prove restore readiness; an
operator-facing command and backup/restore rehearsal remain required.

## Verification and rollback

- Unit tests use protected temporary files and prove active/old/lookup access,
  callback-copy zeroing, close behavior, invalid/missing IDs, duplicate-purpose
  refusal, exact length, permission rejection, and symbolic-link rejection.
- The existing AES-GCM/HMAC suite continues to prove cryptographic shape and
  old-key resolution through the custody port.
- Runtime configuration tests prove the parent-gate dependency, exact boolean,
  bounded key IDs, fixed derived paths, and secret-free configuration. Real
  TLS/WSS, HTTPS and disposable-PostgreSQL integration proves a server-issued
  lease can produce one encrypted subscription row and fixed route telemetry.
- Disposable-PostgreSQL integration also proves the offline rewrite changes
  both the recorded encryption key ID and deterministic endpoint lookup tag,
  refuses an undersized row ceiling before mutation, rolls the entire table
  back after a mid-stream protection failure, and lets account deletion cascade
  through subscription ciphertext.
- Rollback removes runtime references before removing this additive adapter.
  Before target-key activation, rollback keeps the source custody mounted and
  leaves existing ciphertext untouched. After a successful rewrite, rollback
  requires a rehearsed database restore or a reverse rewrite while both key
  sets remain available.
