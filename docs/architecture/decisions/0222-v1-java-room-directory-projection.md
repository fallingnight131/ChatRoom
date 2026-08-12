# ADR-0222: Project the Canonical Directory into V1 Rooms

- Status: Accepted
- Date: 2026-08-13
- Owners: Java backend and protocol compatibility
- Related milestone: M3

## Context

The detached Java V1 compatibility path can authenticate an imported account,
but its first post-login business frame still has no owner. PostgreSQL already
provides the canonical authenticated V2 conversation directory and imported V1
conversation mappings. Querying the old SQLite model from Java or teaching the
canonical directory about numeric V1 identifiers would create a second source
of truth or leak compatibility concerns into the application model.

## Decision

- Add a transport-independent V1 room-directory service in the application
  compatibility boundary.
- Page only the canonical account-authorized conversation directory, retain
  GROUP conversations, and derive unread as `latestSequence-lastReadSequence`.
- Translate canonical UUIDs through one bounded batch lookup per page in the
  isolated V1 mapping port. Missing, extra, duplicate, wrong-kind, or out-of-range
  mappings fail the complete operation; a partial list must never cause clients
  to prune valid local conversations.
- Preserve the established V1 numeric-room ascending order and administrator
  meaning (`OWNER` or `ADMIN`). Bound one request to 1,000 scanned canonical
  conversations.
- Keep this slice detached from Netty. A later handler must add strict JSON
  request/response bounds, off-event-loop execution, safe errors, telemetry,
  and runtime composition before the Java V1 route can serve `ROOM_LIST_REQ`.

## Alternatives Considered

- Read V1 SQLite at runtime: rejected because PostgreSQL is the target authority
  after import and online dual reads would make cutover/rollback ambiguous.
- Add numeric IDs to `ConversationSummary`: rejected because that would spread a
  temporary V1 representation through the canonical V2 model.
- Query one mapping per room: rejected because it creates unbounded N+1 database
  work on the login path.

## Consequences

The compatibility application can now produce an exact, authorized V1 room
list without transport or SQLite dependencies. The current projection does not
provide friends, presence, avatars, room members, history, or message writes and
does not activate a product listener.

## Migration and Rollback

This is additive and read-only. Removing the service and batch port method
restores the prior detached-login state without data migration. PostgreSQL V1
mapping rows remain unchanged.

## Verification

- `./gradlew :application:test :persistence-postgres:test --no-daemon`
- A later real-PostgreSQL handler slice must verify imported account membership,
  mapping completeness, disabled/left membership rejection, executor saturation,
  and old Web/Windows response consumption before activation.
