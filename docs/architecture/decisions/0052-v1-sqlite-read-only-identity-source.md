# ADR-0052: V1 SQLite Read-only Identity Source

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The deterministic import planner needs a faithful projection of the current V1
`users` table, including committed WAL rows and startup-added columns. Opening a
normal writable SQLite connection from migration tooling risks changing pragmas,
creating data, or masking an obsolete schema.

## Decision

- Pin Xerial SQLite JDBC 3.51.2.0 in the Gradle version catalog and dependency
  lock. Keep it inside the PostgreSQL persistence/migration adapter rather than
  leaking SQLite types into application modules.
- Resolve an existing readable regular file before connecting. Open it with the
  SQLite URI `mode=ro`, then enforce `query_only`, foreign keys, and a bounded
  busy timeout on that connection.
- Run `PRAGMA quick_check` and require exactly one `ok` result before reading.
  Require the current migrated `users` columns: ID, username, display name,
  password hash, salt, and creation time. Do not infer missing startup
  migrations.
- Read the minimal identity projection ordered by numeric ID. Support ISO
  instant/offset timestamps plus SQLite `CURRENT_TIMESTAMP` UTC text. An invalid
  or absent timestamp becomes a safe blocking planner issue rather than a parse
  exception containing row data.
- Wrap SQL/path failures in fixed safe migration-source errors without exposing
  source paths, SQL text, usernames, hashes, or salts.
- Do not use `immutable=1`: a legitimate V1 source may have committed rows in
  its WAL. Verified backup and quiescence/cutover policy remain separate gates.

## Consequences

- Dry-run planning sees current committed WAL data while being unable to write
  through its own connection.
- A pre-migration, corrupt, missing, or unreadable V1 database fails before any
  target comparison or write.
- Merely reading a healthy source is not backup evidence and does not authorize
  PostgreSQL apply or traffic cutover.

## Verification

Temporary SQLite tests create WAL-mode current schemas, leave committed rows
visible to another connection, and verify both credential generations and UTC
timestamp forms are projected without changing row count. Tests also cover a
bad timestamp becoming a non-secret issue and a pre-migration schema producing a
fixed safe error without its path.

## Rollback

Remove the unused reader and locked JDBC dependency. It writes neither source
SQLite nor PostgreSQL and does not affect V1 traffic.
