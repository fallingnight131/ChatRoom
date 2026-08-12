# ADR-0281: Use a Server-Keyed Tag for Protected-Room Idempotency

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0280

## Context

Room creation must distinguish an exact retry from conflicting reuse of the
same actor/request ID. Argon2id encodings are deliberately salted and therefore
different for the same password. A plain deterministic SHA-256 password digest
would enable cheap offline guessing and is not acceptable as an idempotency key.

## Decision

Make the room-password output port return two opaque values: the independently
salted slow password-verification encoding and a stable server-keyed,
domain-separated idempotency tag. The future crypto adapter must derive the tag
with HMAC-SHA-256 (or a reviewed equivalent) under a dedicated non-exported key;
it must not reuse session, signing, account-password, or data-encryption keys.

Persist the encoded hash for join verification and the tag only inside the
room-creation idempotency record. Compare the tag in constant time when
classifying retries. Never log, expose, search, or use the tag as authentication
evidence. Key rotation requires a versioned tag format and an explicit migration
or multi-key comparison window before the old key is retired.

Unprotected-room creation carries neither value. This decision adds only the
application value type; key custody, schema, adapter, and rotation remain future
work and the route stays inactive.

## Verification

Application tests prove that only the opaque pair crosses into persistence and
that owned plaintext bytes are still destroyed on every path. Future adapter
tests must prove stable same-password tags, different-password separation,
key/domain separation, conflict classification, and redacted diagnostics.
