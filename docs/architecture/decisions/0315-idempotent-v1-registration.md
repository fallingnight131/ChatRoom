# ADR-0315: Idempotent Secure V1 Registration

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3

## Context

The Java compatibility gateway lacks `REGISTER_REQ`. The C++ implementation
checks username availability before insertion, which races concurrent requests,
and treats a retry after a lost success response as an unrelated username
collision. V1 clients require a positive numeric user ID.

## Decision

- Accept the existing username, display-name, and password fields. Enforce the
  established ASCII username policy (`[A-Za-z0-9_]{6,20}`), a trimmed non-empty
  display name of at most 64 Unicode code points, and a password of 4-1024 code
  points. Own and clear password bytes from strict JSON decoding through hashing.
- Hash with current Argon2id policy before persistence. In one serializable
  transaction, use the unique username constraint to create the account and an
  exact positive V1 numeric mapping. Allocate runtime IDs from a descending
  bounded sequence so imported positive IDs retain their namespace.
- Treat username as the natural retry key. If the existing enabled account has
  the same display name, complete V1 mapping, and matching password, return the
  original ID with `duplicate=true`. Any mismatch or V2-native unmapped account
  returns the existing generic username-taken rejection.
- Apply authentication admission control before Argon2 work. Preserve
  `REGISTER_RSP`; `duplicate` is additive. Do not authenticate automatically or
  expose UUIDs, password material, hashes, salts, or capacity details.

## Consequences

Concurrent registration is decided by PostgreSQL rather than a check-then-write
race, and lost-response retries converge. Usernames remain case-sensitive to
match V1 behavior. Exhausting the numeric compatibility namespace fails closed
and requires an explicit migration decision.

## Verification

Application tests cover validation, secret closure, normalization, hashing,
exact retry, wrong-password collision, and unmapped collision. PostgreSQL tests
cover concurrent uniqueness, atomic mapping, capacity bounds, and clean restart.
Gateway tests cover strict decoding, admission, bounded workers, generic errors,
and registration followed by login.
