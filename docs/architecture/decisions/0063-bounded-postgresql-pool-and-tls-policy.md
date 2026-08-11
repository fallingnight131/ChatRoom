# ADR-0063: Bounded PostgreSQL Pool and TLS Policy

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The identity adapter accepts a `DataSource`, but the gateway composition root had
no production connection ownership policy. Opening a new physical connection
for every authentication would add latency and database load; an unbounded pool
would move overload into PostgreSQL. The previous runtime parser also accepted
remote JDBC URLs without authenticating the database endpoint.

## Decision

- Use HikariCP 7.0.2 behind the gateway runtime boundary. The version is the
  Java 11+ release documented by the project and published in Maven Central;
  application/domain modules remain unaware of the pool implementation.
- Configure explicit maximum/minimum-idle sizes, acquisition/validation
  timeouts, idle/max lifetime, TCP keepalive, and fail-fast initialization.
  Default maximum size is eight and remains a deployment input, not a capacity
  claim. Route SLF4J pool diagnostics through the JDK logging backend.
- Require `sslmode=verify-full` exactly once for PostgreSQL JDBC URLs. Reject URL
  user info, embedded `user`/`password` query properties, duplicate query keys,
  fragments, missing database paths, and malformed query properties.
- Permit a non-verified JDBC URL only when
  `CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL=true` and the URL host is a numeric
  loopback literal. The exception is for isolated local development and must not
  accept DNS names or remote addresses.
- Keep PostgreSQL username/password in dedicated environment/secret values and
  out of string representations. Pool construction remains fail-before-bind.

## Consequences

- Authentication and session operations reuse a bounded set of physical
  connections and fail startup when the database cannot be reached within the
  configured initialization window.
- Production deployment must provide a database hostname matching its trusted
  certificate and PostgreSQL root-certificate configuration understood by the
  JDBC driver.
- Pool sizing must be evaluated together with authentication worker count,
  future messaging transactions, PostgreSQL `max_connections`, and replica
  topology before traffic cutover.

## Verification

Unit tests inspect the pool configuration without connecting and cover defaults,
pool bound relationships, secret-safe text, remote TLS enforcement, explicit
numeric-loopback exception, invalid booleans, duplicate SSL modes, and embedded
credential rejection. PostgreSQL integration remains covered by the repository
PostgreSQL gate; the later composition-root slice will exercise startup against
that gate.

## Rollback

Remove the gateway pool factory/dependencies and new configuration fields. No
active runtime constructs the pool yet, so rollback does not change production
traffic or data.
