# ADR-0312: Secure Convergent V1 Room Password State

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3

## Context

V1 administrators can set, replace, cancel, and query the presence of a room
join password. Existing clients send plaintext only in `SET_ROOM_PASSWORD_REQ`
and expect `hasPassword`; they never need the password back. The Java room
creation and join paths already use Argon2id and a server-keyed, domain-separated
HMAC tag, but the canonical credential row stores only the randomized Argon2id
hash. Without a stable opaque tag, retrying the same password after a lost
response appears to be a new change and repeats room-wide effects.

## Decision

- Define separate status and update use cases. Both bind the actor to the
  authenticated connection and require an enabled active OWNER/ADMIN in an open
  mapped GROUP. Status returns only `hasPassword` and update metadata.
- Treat an empty password as cancellation. A non-empty password must be valid
  UTF-8 containing 4-1024 Unicode code points, matching room creation policy.
- Own password bytes with clearable buffers from JSON decoding through
  application completion. Never log, return, retain, or persist plaintext.
- Reuse the existing `LegacyV1RoomPasswordEncoder`: persist its Argon2id hash for
  slow verification and its domain-separated HMAC-SHA256 tag solely for
  equality/idempotency. Neither value is exposed to V1 clients.
- Expand `group_join_credential` with a nullable bounded tag. New room creation
  writes it. Existing rows remain valid with null tags; their first password
  reset upgrades the row. A matching stored tag returns `changed=false` without
  replacing the randomized hash or emitting another system message.
- Cancellation deletes the credential row. Repeated cancellation converges to
  `changed=false`. Insert/update/delete and authorization occur in one
  serializable transaction using database time.
- Preserve `SET_ROOM_PASSWORD_RSP` and `GET_ROOM_PASSWORD_RSP`. Only a changed
  update emits the existing room `SYSTEM_MSG`; no password-status notification
  containing secret material is introduced.
- Keep the slice in the detached V1 compatibility module until formal product
  traffic cutover gates are satisfied.

## Consequences

The canonical credential remains independently verifiable by Argon2id while
same-password retries become deterministic. HMAC key rotation needs an explicit
versioned transition because an old tag cannot be recomputed from the Argon2id
hash; authentication remains functional during such a transition.

Rollback to code that ignores the nullable tag leaves password verification
working but loses retry convergence for subsequent updates. Dropping the column
is unnecessary during the compatibility window.

## Verification

- application tests prove UTF-8/code-point bounds, clearable ownership, set,
  cancel, status, and persistence identity checks;
- migration tests prove clean/restart schema, nullable legacy compatibility,
  tag constraints, and new-room tag persistence;
- PostgreSQL tests cover authorization, first set, exact retry, replacement,
  cancellation, repeated cancellation, and join with old/new passwords;
- gateway tests cover strict secret decoding/clearing, compatible responses,
  changed-only system effects, malformed input, saturation, and relogin status.
