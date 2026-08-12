# ADR-0293: Define Bounded V1 Room Member Directory

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Follows: ADR-0292

## Decision

Define a transport-independent `USER_LIST_REQ` boundary. The actor comes only
from authenticated server state and the client supplies one positive 32-bit
legacy room ID. Persistence must authorize the actor as an active member of an
active mapped GROUP before returning active enabled members with complete V1
identity mappings and canonical OWNER/ADMIN/MEMBER role.

Return at most 1,000 members. Persistence receives `limit + 1` so overflow is
detected rather than silently truncated; overflow is a stable `ROOM_TOO_LARGE`
rejection until a paged V2 member directory exists. Require the authenticated
actor in the complete projection, reject duplicate account IDs/usernames, and
fail closed on partial mappings.

Durable PostgreSQL rows do not store online state. The application service asks
an ephemeral presence port only after authorization and overlays `isOnline` on
the bounded account set. The V1 response contains username, display name,
administrator flag, and online flag; canonical account UUIDs remain internal.

No adapter or handler exists yet. Rollback removes the unused application
boundary and has no data effect.

## Verification

Application tests prove actor/room/limit binding, role and presence projection,
invalid/unauthorized/overflow rejection without presence lookup, and fail-closed
missing actor, duplicate username, or foreign presence results.
