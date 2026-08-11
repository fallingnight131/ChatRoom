# ADR-0051: V1 Identity Import Mapping and Pre-write Validation

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

V1 user primary keys are SQLite integers while V2 accounts use UUIDs. A one-way
import must be repeatable across dry runs and retries, preserve exact
case-sensitive usernames and both supported credential generations, and stop
before target writes if source material is ambiguous or invalid.

## Decision

- Project only the required V1 identity fields into a raw migration record, then
  pass every row through a pure deterministic planner before comparing or
  writing PostgreSQL.
- Map positive V1 user ID `N` to an RFC 4122 version-5 UUID derived from a fixed
  chat-room namespace and the UTF-8 name `v1-user:N`. Row order, machine, dry
  run, and retry therefore produce the same account identity.
- Preserve the exact V1 username as `username_key`; do not case-fold or rename
  during import. Validate target byte/character bounds and reject duplicate
  numeric IDs or usernames.
- Accept only structurally valid libsodium-compatible Argon2id encodings with no
  legacy salt, or exactly 64 hexadecimal legacy SHA-256 characters with a
  1..512-character salt. Never silently repair, truncate, reset, or discard a
  credential.
- Require a creation timestamp and a non-empty 1..100-character display name.
  Missing values are reportable migration issues rather than inferred data.
- Sort by legacy numeric ID and calculate a length-delimited SHA-256 source
  fingerprint through a streaming digest. No additional canonical byte buffer
  containing credential fields is retained.
- Block an empty source and block the whole plan on any issue. Reports contain
  only legacy numeric ID, fixed code, and safe description—never username,
  display name, hash, or salt.

## Consequences

- Identity references imported by later conversation/message slices can reuse
  the same deterministic mapper without a mutable mapping table.
- Rerunning the planner produces an identical fingerprint and UUID plan for an
  unchanged source, enabling target comparison and idempotency checks.
- Actual SQLite reading, verified backup, PostgreSQL conflict comparison/write,
  and post-write reconciliation remain mandatory follow-up gates.

## Verification

Unit tests cover order independence, stable UUID version/variant, both
credential generations, salt removal for Argon2id, duplicate IDs/usernames,
malformed credentials, display/timestamp failures, empty source, and explicit
absence of representative sensitive strings from the issue report.

## Rollback

Remove the unused planner. It performs no I/O and changes neither authoritative
V1 SQLite nor target PostgreSQL data.
