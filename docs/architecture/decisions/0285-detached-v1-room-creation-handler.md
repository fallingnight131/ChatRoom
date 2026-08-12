# ADR-0285: Compose Detached V1 Room Creation Handling

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0284

## Decision

Compose strict bounded `CREATE_ROOM_REQ` handling in the detached Java V1
compatibility module. Bind creator identity to authenticated channel state and
use the required outer envelope `id` as the idempotency key. Accept only
`data.roomName` and optional `data.password`; an empty password remains the V1
unprotected-room form. Copy password text directly to owned UTF-8 bytes, erase
codec, handler, and application copies deterministically, and never log it.

Execute at most one creation per channel off the event loop. Return compatible
`CREATE_ROOM_RSP` fields `success`, positive `roomId`, normalized `roomName`, and
`isAdmin: true`; add `duplicate` as an ignorable field for upgraded clients.
Return stable error codes for invalid input, denied creation, and request-ID
conflict. These business results keep the connection usable; malformed,
concurrent, saturated, dependency-failed, encoding-failed, or stale work fails
closed.

The detached module requires explicitly parsed runtime key material, owns a
separate password encoder, and zeros it on module close. Fixed telemetry records
only first/duplicate/rejection outcome, duration, failure, and saturation; it
contains no request, room, account, password, hash, or tag identity. Creation
does not fabricate a join notification because the creator is already the sole
active OWNER.

The product listener remains unchanged. Rollback removes the handler from the
detached module; V023 durable rooms remain valid and discoverable through the
directory.

## Verification

Codec/handler tests prove exact fields, envelope-ID and authenticated-actor
binding, password transfer into the secure application boundary, UUID-free
compatible response, business conflict continuity, downstream pass-through,
malformed/dependency/saturation closure, and module key cleanup. Disposable
PostgreSQL proves imported login, protected-room first creation, exact-frame
duplicate with the same room ID, conflicting reuse, Argon2id plus keyed-tag
storage, and replacement-login recovery through `ROOM_LIST_RSP`.
