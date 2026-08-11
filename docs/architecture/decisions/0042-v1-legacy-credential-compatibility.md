# ADR-0042: V1 Legacy Credential Compatibility and Upgrade

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

Some V1 accounts may still contain the pre-M1 `SHA-256(UTF8(password + salt))`
credential because users migrate only after a successful login. Requiring every
user to log into V1 before Java cutover would lock out dormant accounts and make
the migration operationally fragile.

## Decision

- Add forward migration V002 with explicit `ARGON2ID`/`V1_SHA256` scheme and
  nullable legacy salt. Enforce matching material: Argon2id prefix with no
  legacy salt, or exactly 64 hexadecimal SHA-256 characters plus a 1..512
  character salt.
- Represent stored credentials as a sealed application type. Database rows and
  legacy fields do not leak into gateway/protocol models.
- Reproduce V1 bytes exactly: concatenate valid UTF-8 password bytes and UTF-8
  salt bytes, calculate SHA-256, and compare in constant time.
- Every legacy verification also performs the standard Argon2id dummy derivation
  before returning, reducing the obvious fast-hash timing distinction from an
  unknown account. Gateway rate limits and bounded workers remain mandatory.
- Return `VERIFIED_NEEDS_UPGRADE` for a correct legacy credential. Hash the same
  short-lived password with current Argon2id policy and replace through a
  compare-and-set on account ID plus old scheme/hash/salt. Concurrent password
  changes therefore win and are never overwritten.
- If rehashing or CAS persistence fails, allow the already verified login and
  mark `credentialUpgradePending` in the internal application result. The
  gateway/observability slice must count this by non-secret category without
  exposing it to clients.
- Generate new Argon2id strings with Bouncy Castle 1.85, `m=65536,t=2,p=1`, a
  fresh 16-byte `SecureRandom` salt, 32-byte output, and libsodium-compatible
  unpadded Base64 encoding.

## Consequences

- Dormant V1 accounts can be imported without a forced password reset and
  upgrade on first successful Java login.
- The actual one-way V1 data-import job, backup, ID mapping, dry run, and rollback
  report are still required before PostgreSQL becomes authoritative.
- Legacy verification remains compatibility-only and must be monitored until no
  legacy rows remain; removal requires a later contract migration and ADR.

## Verification

- Java tests cover Unicode V1 password+salt bytes, correct/wrong/malformed
  legacy hashes, Argon dummy execution path, current-policy rehash generation,
  random-salt variation, successful/pending application upgrade outcomes, and
  session issuance after verified legacy login.
- Disposable PostgreSQL 17.10 tests apply V001 then V002, reject inconsistent
  credential rows, map both schemes, perform one successful CAS upgrade, and
  reject a stale second CAS.
- Full Java and PostgreSQL gates pass; no listener or V1 data changes.

## Rollback

V1 SQLite remains authoritative. The additive PostgreSQL schema is unused by
product traffic. Remove the unused adapters/database or restore the pre-migration
database; do not edit an applied V002 checksum.
