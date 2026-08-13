# ADR-0326: Windows Installation Device Identity

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

V2 binds sessions to a client-provided canonical device UUID. Generating a new
UUID on every process start would create duplicate device rows and make the
security directory misleading. Reusing an update-rollout identifier or generic
QSettings would couple unrelated lifecycles and weaken corruption handling.

## Decision

- Give the supported Windows installation one random RFC 4122 version-4 UUID,
  stored under its application-local security directory independently of
  account, V1 cache, update rollout state, and V2 session credentials.
- Store an exact versioned JSON document using an atomic `QSaveFile`, a bounded
  lock wait, and owner-only file/directory permissions.
- Reject relative paths, symlink boundaries, oversized files, unknown keys,
  invalid JSON, noncanonical UUIDs, and lock/write failures.
- Never silently regenerate a corrupt or unsafe existing identity. Disable the
  V2 device-management preview with a safe diagnostic until the local state is
  repaired deliberately.
- Do not delete the installation identity on account logout or cache clearing;
  it identifies the installation, not a user session or account.

## Consequences

All accounts using one Windows installation reuse the same client installation
identifier while the server still creates account-owned device rows. No token,
password, account identifier, or device directory is stored in this file.
Uninstall-data policy can preserve the identity alongside other account-local
data; a future explicit “reset this installation” UX requires its own safety
decision.

## Verification

- create an owner-only canonical identity and reload it unchanged;
- serialize concurrent access with the lock and write atomically;
- reject corrupt, unknown, oversized, relative, and symlinked state without
  replacing it;
- confirm cache clearing and logout do not invoke identity deletion.
