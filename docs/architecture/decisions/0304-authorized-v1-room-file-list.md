# ADR-0304: Project room files only from canonical authorized attachment state

- Status: Accepted
- Date: 2026-08-13

## Context

V1 `ROOM_FILES_REQ` powers the administrator file manager. The Qt server reads
active SQLite file rows and returns legacy numeric IDs, names, sizes, timestamps,
and room quota usage. The Java compatibility path must not expose UUIDs, list
unsealed/revoked objects, infer missing mappings, or authorize an ordinary room
member merely because they can read message history.

## Decision

Add a read-only compatibility application boundary and PostgreSQL adapter. A
request is authorized only for an enabled, mapped account with active `OWNER` or
`ADMIN` membership in an active mapped GROUP. The projection requires complete
room file and message mappings, one canonical attachment message, reviewed V1
file/image/video content type, and attachment state `READY`.

Return at most the canonical room `max_file_count` upper bound of 1,500 rows,
ordered by canonical creation time and legacy file ID descending. The response
contains only the V1 numeric file ID, safe filename, byte size and timestamp.
Used space is the exact sum of returned ready files; maximum space comes from the
canonical group resource policy. `UNAVAILABLE`, pending, and revoked attachments
are excluded.

Any incomplete/oversized/inconsistent authorized projection fails as a
dependency error rather than returning a partial list. Missing room, dissolved
room, disabled actor, non-admin role, or missing identity mapping share one
non-enumerating denial.

## Consequences

- File-manager reads cannot become an object authorization or UUID disclosure
  channel.
- Cleared historical file messages remain visible in conversation history but
  do not appear as manageable active files.
- The detached V1 gateway handler may serialize this application result without
  querying storage or deciding authorization itself.
