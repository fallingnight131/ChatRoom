# ADR-0041: Java Verification of V1 Libsodium Argon2id Hashes

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The identity application port requires real password verification before the
gateway can authenticate. V1 stores libsodium self-describing Argon2id strings,
so Java must prove cross-implementation compatibility rather than create a new
password format. Unknown accounts also need expensive dummy work to reduce
username-enumeration timing differences.

## Decision

- Add an `identity-crypto` outward adapter module depending only on the
  application port and Bouncy Castle Java 1.85.
- Strictly parse `$argon2id$v=19$m=...,t=...,p=...$salt$hash` and use Bouncy
  Castle's maintained Argon2id implementation. Do not implement Argon2 itself.
- Bound encoded input to 512 characters, memory to 64 MiB, iterations to 4,
  lanes to 4, salt/hash to 16..64 bytes, and require the Argon2 v1.3 format.
  Corrupt or out-of-policy database values fall back to dummy work and reject.
- Use one fixed, non-secret libsodium interactive hash as the unknown-account
  dummy. Missing and malformed hashes still perform the standard 64 MiB/t=2/p=1
  derivation and always reject.
- Compare derived and expected bytes with `MessageDigest.isEqual`, clear derived
  bytes and parameter-owned salt, and rely on the application secret container
  to clear the callback password copy.
- Authentication hashing must later run on a bounded worker pool behind
  per-IP/account/gateway rate limits, never on a Netty event loop.
- This adapter supports Argon2id only. V1 legacy salted-SHA rows require a
  documented import/upgrade compatibility slice before Java identity cutover;
  no dormant account may be silently locked out.

## Evidence

On 2026-08-11, the repository's installed libsodium 1.0.20 generated the fixed
test vector for the explicit test password `java-v2-test-password` using
`crypto_pwhash_str` interactive limits (`m=65536,t=2,p=1`). The Bouncy Castle
adapter verifies that vector, rejects a wrong password, and uses the same vector
for non-secret dummy work. The encoded hash is test data, not a credential.

Tests also reject unsupported algorithms/versions, excessive parameters,
overlong encodings, missing hashes, and malformed hashes. The full Java module
gate runs with warnings as errors and the Bouncy Castle dependency is locked.

## Consequences

- Modern V1 password hashes can be verified without a native Java libsodium
  runtime.
- One verification intentionally consumes about 64 MiB and is not throughput
  evidence; capacity and worker sizing require benchmarks after dispatch exists.
- Weak/nonstandard hashes may have different work factors. The V1 import audit
  must inventory parameters and complete rehash/reset policy before cutover.

## Rollback

No gateway or production route invokes the adapter. Remove the unused module and
dependency; V1/libsodium authentication remains authoritative.
