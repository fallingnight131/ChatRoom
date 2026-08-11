# ADR-0035: V2 PostgreSQL Migration Foundation

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The V2 Java workspace needs a durable target model before repositories and data
import are implemented. The current V1 SQLite schema mixes legacy row shapes
with protocol behavior and has no versioned migration history. Creating tables
without testing a real PostgreSQL restart would provide weak migration evidence.

## Decision

- Add `persistence-postgres` as an outward adapter module that depends on the
  transport-independent application core, never the reverse.
- Use PostgreSQL with Flyway 13.0.0 forward-only SQL migrations and pgJDBC
  42.7.11. Lock resolved dependencies in Gradle.
- Put V2 server truth in a dedicated `chat` schema and disable Flyway `clean` in
  application code. Released versioned migrations are immutable.
- Start with accounts, Web/Windows devices, hashed sessions, canonical
  conversations/members, direct-pair uniqueness, and messages with database
  sequence and idempotency constraints.
- Use application-generated UUIDs, database timestamps, bounded payloads, token
  digests, foreign keys, and explicit supported-value checks.
- Keep V1 SQLite authoritative. This foundation neither imports data nor enables
  a Java traffic route.

## Alternatives

- Reuse SQLite for V2: rejected because it does not meet the documented
  multi-instance target and would require a second durable migration later.
- Let an ORM generate/update production schema: rejected because repeatable
  review, checksums, deployment ordering, and restore planning would be weaker.
- Add down migrations: rejected because destructive downgrade automation is less
  reliable than additive compatibility plus a verified backup/PITR restore.
- Introduce PostgreSQL only when the first listener is enabled: rejected because
  schema and migration failures should be found before traffic is possible.

## Verification

- A disposable PostgreSQL 17.10 cluster on the macOS development host applied
  V001, reapplied zero migrations on restart, and passed Flyway validation.
- Integration tests verify the exact application table set, atomic sequence
  allocation, sender/client idempotency uniqueness, and conversation-sequence
  uniqueness.
- CI runs the same disposable-cluster verifier; CI success is required before
  claiming its bundled PostgreSQL version as additional evidence.
- The complete Java workspace compiles on JDK 21 with warnings as errors.

## Rollback

No product route or V1 data changes. The module and unused database can be
disabled while V1 remains authoritative. Any later slice that makes PostgreSQL
authoritative must require a verified backup/restore point and its own cutover
ADR before traffic changes.
