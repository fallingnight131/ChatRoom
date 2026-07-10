# ADR-0004: Upgrade V1 Password Hashes to Argon2id on Login

- Status: Accepted
- Date: 2026-07-11
- Owners: project maintainers
- Related milestone: M1

## Context

V1 stored a single SHA-256 digest of `password + salt`. That construction is
fast and therefore cheap to brute-force after a database leak. Existing users
cannot all be rehashed offline because the server does not know their plaintext
passwords. Requiring a simultaneous password reset would break existing Qt and
Web clients and disrupt every account.

The repository also builds the server on Linux, macOS, and Windows, so the
password implementation must come from a maintained cross-platform cryptography
library rather than project-authored Argon2 code.

## Decision

- Use libsodium's high-level `crypto_pwhash_str` interface with its interactive
  limits. In the selected libsodium versions this produces an Argon2id encoded
  string containing algorithm, version, parameters, hash, and random salt.
- Keep the encoded value in the existing `users.password_hash` column and store
  an empty string in the legacy `salt` column for new hashes.
- Treat a valid 64-character legacy SHA-256 digest plus its existing salt as a
  compatibility format only.
- After a successful legacy login, create and persist an Argon2id hash before
  returning success. A wrong password never changes the row.
- Use `crypto_pwhash_str_needs_rehash` so a successful login also upgrades older
  Argon2 parameters.
- New registration and password changes write only Argon2id hashes.
- If optional login-time rehashing cannot allocate resources or persist, allow
  the already verified login and emit a user-ID-only operational warning. New
  registration/password change fails closed if hashing fails.
- Keep cryptographic operations in `PasswordHasher`; `DatabaseManager` owns SQL
  and migration timing.

## Alternatives Considered

- Keep salted SHA-256: rejected because it is deliberately fast and not a
  password hashing function.
- Implement Argon2 or bcrypt inside the repository: rejected because custom
  cryptographic implementations create unacceptable review and maintenance
  risk.
- Use PBKDF2 from Qt only: rejected for the default because a supported
  memory-hard construction is available across all target build systems.
- Reset every password at once: rejected because it breaks compatibility and
  creates avoidable product/support risk.
- Add a new schema column immediately: not required because the libsodium string
  is self-describing; retaining the legacy salt column keeps migration additive.

## Consequences

Interactive hashing currently reserves about 64 MiB per operation, so login and
registration rate limiting must follow before public deployment. Authentication
is intentionally slower than SHA-256. Native server builds gain a libsodium
runtime dependency, which CI installs and bundles into verification artifacts.

New Argon2id rows cannot be read by server binaries from before this decision.
Once any account is upgraded, rollback requires a compatible authentication
adapter or a database restore; code-only rollback is unsafe.

## Migration and Rollback

Back up the SQLite database before deploying this change. Roll out one compatible
server version, observe hash-upgrade errors, and let users migrate on successful
login. The old `salt` column remains untouched for accounts that have not logged
in and becomes empty only when a row receives an Argon2id hash.

Do not deploy an older binary after migration has started. Emergency rollback
must restore the pre-deployment database or retain `PasswordHasher` support in
the rollback build.

## Verification

- New registration stores `$argon2id$...` and an empty legacy salt.
- Correct and incorrect modern-password verification are covered.
- Incorrect legacy login leaves the SHA-256 row unchanged.
- Correct legacy login upgrades the row and remains valid after restart.
- Password change rejects a wrong old password and stores only Argon2id.
- A valid hash with weaker parameters is upgraded on login.
- Database schema, V1 TCP flows, and cross-platform server builds remain CI
  gates.
