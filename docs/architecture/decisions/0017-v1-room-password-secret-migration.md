# ADR-0017: Protect V1 Room Password Secrets

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

The `rooms.password` column stored recoverable plaintext. Join compared that
value directly, and an administrator-only `GET_ROOM_PASSWORD_RSP` returned the
secret to clients. A database snapshot, log/debug inspection, or compromised
administrator session could therefore disclose every room password. Web room
creation also sent an optional password that the server silently ignored.

Existing deployments may contain plaintext rows, so replacing them all offline
is impossible without knowing each password or forcing every room administrator
to reset it.

## Decision

- Hash every new, created, or changed room password with the existing
  libsodium Argon2id policy and keep the encoded value in the existing nullable
  `rooms.password` column.
- Verify modern hashes through `PasswordHasher`. A successful comparison with a
  legacy plaintext row immediately replaces it with Argon2id using a conditional
  update; a failed comparison never mutates the row.
- Make optional password creation effective and atomic with room creation.
- Validate non-empty room passwords with the existing V1 password bounds.
- Route protected-room verification through the bounded single-node
  authentication-abuse guard before invoking Argon2id.
- Redefine `GET_ROOM_PASSWORD_RSP` as an administrator-only status query. It
  returns `hasPassword` and never returns `password`.
- Update Web and Windows settings UI to use masked input and explain that a
  secret can be reset but not viewed.

The wire message names remain unchanged for compatibility. Older clients can
still join and set passwords; their obsolete “view” UI receives no secret.

## Consequences

Room passwords are no longer recoverable by the product or database operators.
Administrators who forget one must replace or clear it. Argon2id deliberately
adds CPU and memory cost to first joins; existing authentication abuse controls
and future gateway controls must account for room-password verification too.

An older server cannot compare migrated Argon2id rows as plaintext. Once any row
is created or upgraded, code-only rollback to a pre-ADR binary is unsafe.

## Verification and Rollback

The password migration test verifies new room hashes, wrong-password
non-mutation, plaintext upgrade, restart verification, and clearing. The V1
smoke suite verifies protected creation, missing/wrong/correct join behavior,
and that the status response contains no password field. Web and Qt Release
builds verify the updated settings contracts.

Rollback must retain Argon2id verification or restore a pre-migration database.
Never convert hashes back to plaintext.
