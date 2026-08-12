# ADR-0283: Encode Room Passwords with Argon2id and Domain-Separated HMAC

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0281

## Decision

Implement the room-password output port in `identity-crypto`. Reuse the current
libsodium-compatible Argon2id policy (`m=65536 KiB`, `t=2`, `p=1`, random
16-byte salt, 32-byte result) for join verification. Independently compute the
stable retry tag as HMAC-SHA-256 over the exact UTF-8 password bytes, prefixed by
the fixed domain `chat-room:v1-room-password-idempotency:v1\0`.

Require exactly 32 random key bytes and encode the tag as
`hmac-sha256:v1:<base64url-without-padding>`. Copy key material on construction,
never mutate the caller's bytes, instantiate HMAC state per operation, zero the
derived tag buffer, and zero the owned key on close. Reject use after close and
do not provide an unkeyed or default-key constructor.

The detached V1 compatibility composition must later require an explicitly
validated runtime key and own this adapter's close lifecycle before the room-
creation handler can be installed. This slice does not add environment parsing,
persist a key, log a tag, or activate a route.

## Verification

Crypto tests prove two hashes of the same password receive different Argon2id
salts but the same tag; the slow hash verifies through the compatibility
verifier; different passwords, keys, and domains produce different tags; the
tag shape matches V023; wrong key length and use-after-close are rejected; and
the caller's key array is unchanged.
