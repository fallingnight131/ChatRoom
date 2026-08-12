# ADR-0284: Require Explicit V1 Room Password Runtime Key Material

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0283

## Decision

Reserve `CHATROOM_V1_ROOM_PASSWORD_HMAC_KEY_BASE64` for the detached Java V1
compatibility composition. Require canonical padded Base64 representing exactly
32 random bytes. Missing, blank, malformed, non-canonical, weak-length, or
already-closed material prevents construction; there is no development default.

Decode into temporary bytes, copy into the existing close-zeroing secret owner,
and erase the temporary array immediately. Expose only construction of a room-
password encoder, which takes its own key copy. Never expose decoded bytes,
include the value in diagnostics, or source it from a command-line option.

Production operators must inject this value through their secret manager and
retain it across restarts. Rotation is not yet supported: changing it while
existing V023 idempotency rows remain would classify same-password retries as
conflicts. A future versioned multi-key migration must precede rotation.

This parser is not wired into the product runtime or listener in this slice.
Rollback removes the unused parser with no durable changes.

## Verification

Tests prove missing, malformed, unpadded non-canonical, and 31-byte values are
rejected; a canonical 32-byte value creates a functioning encoder; closing key
material prevents later encoder construction.
