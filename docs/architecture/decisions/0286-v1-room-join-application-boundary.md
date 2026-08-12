# ADR-0286: Define the V1 Room Join Application Boundary

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Follows: ADR-0285

## Decision

Define `JOIN_ROOM_REQ` as an authenticated server-authorized application use
case before adding persistence or transport. The command contains the
server-bound account, one positive V1 room ID, and optional owned UTF-8 password
bytes. It destroys the password on every return and exception path and accepts
at most 1024 Unicode code points.

The read boundary returns either an existing active membership, a completely
mapped GROUP candidate with its optional typed stored credential, or a fixed
rejection. Existing membership succeeds idempotently without rechecking the
password. A protected first join distinguishes a missing password from a failed
verification and performs slow verification through `CredentialVerifierPort`.
Canonical UUIDs and credential material remain internal.

After verification, the service passes the exact conversation, room, account,
and credential snapshot to a separate atomic mutation. Its PostgreSQL adapter
must compare that snapshot while rechecking account eligibility, active
membership, target kind/mapping, and the bounded member limit. This prevents a
lookup-to-write race from authorizing changed access policy. Concurrent joins
may converge to `newJoin: false`; only a committed first join may later produce
a `USER_JOINED` notification intent.

Stable rejections are invalid input, not found, password required, invalid
password, room full, join denied, and access changed. Transport localization is
not part of the application result. The product listener remains unchanged.

Rollback removes this unused application boundary. It has no schema, listener,
or externally visible behavior.

## Verification

Application tests prove protected verification and exact snapshot binding,
password-free existing-member idempotency, missing/wrong/malformed password
handling, access and atomic capacity rejections, identity/target substitution
failure, and deterministic secret cleanup.
