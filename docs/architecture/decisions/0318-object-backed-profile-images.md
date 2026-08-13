# ADR-0318: Object-Backed Profile Images

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3
- Follow-up: ADR-0319 leased profile-image cleanup

## Context

V1 user and room avatars are Base64 inside JSON and SQLite stores decoded BLOBs.
That duplicates bytes across WebSocket frames, database memory, caches, and
broadcasts. The target architecture keeps file bytes out of PostgreSQL and chat
messages, but supported V1 Web/Windows clients still require the established
avatar messages during their compatibility window.

## Decision

- Store canonical avatar bytes in private object storage. PostgreSQL stores only
  owner/room identity, immutable object key, exact size, SHA-256, `image/png`,
  dimensions, version, update time, and cleanup state; it never stores Base64 or
  image bytes.
- Treat V1 Base64 as a gateway-only compatibility adapter. Decode strictly into
  an owned buffer capped at 256 KiB, inspect under bounded decoder resources,
  reject oversized dimensions/decompression bombs, strip metadata by decoding,
  and re-encode canonical PNG before object persistence.
- Use content-addressed immutable object keys and checksum-verified create-only
  writes. Commit the new PostgreSQL pointer only after object evidence matches;
  mark an unreferenced replaced object for asynchronous delete-confirm cleanup.
  Exact content retry converges without a new version or repeated notification.
- A user may mutate only its authenticated account avatar. Only an active room
  administrator may mutate a room avatar. User reads resolve enabled mapped
  accounts; room reads require active membership. Object keys/provider URLs never
  cross V1 JSON.
- Preserve `AVATAR_*` and `ROOM_AVATAR_*` names/fields for V1. The compatibility
  read adapter may fetch at most one verified 256-KiB object and Base64-encode it
  at the edge. V2/Web upgrade paths use short-lived authorized object reads and
  version metadata instead of Base64 broadcast.
- Keep the entire slice detached until the dated real-provider capability gate
  passes. Existing C++/SQLite remains authoritative and is the rollback path.

## Consequences

The modern path avoids durable BLOBs and broadcast amplification while old
clients remain supportable. A failed database commit can leave an unreferenced
immutable object, so cleanup state and metrics are mandatory before activation.
Object storage failure cannot be reported as a committed avatar change.

Historical avatar import requires a separately verified SQLite extraction and
object manifest; missing bytes must remain an explicit absent avatar rather than
fabricated evidence. The image inspection adapter and object-provider acceptance
remain implementation gates.

As of 2026-08-13, the bounded PNG inspector, V040 metadata/read adapters,
application-level private-object verification, S3 checksum-bound reader, and
strict detached V1 user/room read handlers are implemented and locally tested.
The inactive mutation service and PostgreSQL guard now also enforce preflight
authorization, object-before-pointer ordering, and serializable unreferenced-
object cleanup intent. They are intentionally absent from runtime composition;
the S3 create-only write adapter and strict detached V1 upload handler are
implemented but still lack real-provider evidence. Historical import and
restart recovery also remain activation gates.

Cleanup claim concurrency and confirmed-object revival follow ADR-0319.

## Verification

- application tests cover owned byte limits, canonical PNG/dimension/digest
  invariants, immutable evidence, and defensive copying;
- private-read tests cover authorization-before-fetch, missing-object and
  metadata/object disagreement, payload ownership/clearing, S3 404 mapping,
  checksum-enabled GET, exact length/type/hash, bounded Base64 responses, strict
  request fields, saturation, and object-key non-disclosure;
- object-write tests cover exact create-only PUT constraints, returned checksum,
  412-to-HEAD retry convergence, mismatched existing evidence, and provider
  denial;
- gateway mutation tests cover strict/canonical Base64 and its exact decoded
  bound, authenticated account/room targets, compatible response fields,
  clearable ownership, first-only local notification, authorization rejection,
  saturation, and object-evidence non-disclosure;
- decoder tests must cover malformed images, metadata stripping, pixel bombs,
  timeout/memory bounds, and deterministic output;
- PostgreSQL/object tests must cover first apply, exact retry, authorization,
  concurrent replacement, object-before-pointer failure, cleanup retry, and
  restart recovery;
- gateway tests must cover strict Base64, compatible bounded output, old-client
  fields, first-only notification, slow consumers, and no object-key leakage.
