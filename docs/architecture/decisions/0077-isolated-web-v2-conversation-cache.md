# ADR-0077: Isolated Web V2 Conversation Cache

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

V2 sequences are unsigned on the wire but intentionally constrained to the
server's positive signed 64-bit range. The V1 Web cache normalizes cursors through
JavaScript `Number`, which silently rounds values above 2^53. Adding a V2 store
to the existing IndexedDB database would require raising its version; rolling
back to an older V1 asset would then fail opening that newer database.

## Decision

- Create a separate version-1 `chat-room-client-v2` IndexedDB database with one
  `v2Conversations` object store. Do not change the live V1 database version.
- Partition keys by authenticated V2 account UUID and conversation UUID.
- Persist server sequences and the contiguous cursor as canonical nonnegative
  decimal strings bounded to the signed 64-bit server range. Never pass them
  through `Number`.
- Retain at most 500 accepted text-message metadata records plus 100 unresolved
  outbox records per snapshot. Whitelist fields
  at the write/load boundary; exclude tokens, authorization, temporary URLs,
  blobs, byte buffers, and media payloads.
- Serialize writes through the existing repository queue. IndexedDB remains a
  recoverable cache; PostgreSQL and sequence synchronization remain authoritative.

## Consequences

V2 application work can hydrate exact cursors without corrupting large sequence
values or changing V1 storage. The additional browser database is inert when V2
is disabled. Directory metadata, drafts, normalized rows, quota telemetry, and
cache-management UI remain later slices.

## Verification

Tests cover exact values above 2^53, negative/out-of-range rejection, field
whitelisting, the independent 500/100 boundaries, account/conversation keys, isolated
database creation, round trip, removal, and preservation of the V1 schema
version. The complete Web test and production-build gate remains required.

## Rollback

Remove the V2 repository methods and ignore the separate database. The V1
database remains at version 3 and continues to open. Deleting cached V2 data is
optional and must not be part of rollback.
