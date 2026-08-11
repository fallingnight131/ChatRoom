# ADR-0023: Windows Local Conversation Repository

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M2

## Context

The supported Windows Qt client keeps messages, room/direct cursors, and drafts
inside `ChatWindow` and `MessageModel`. A restart therefore discards the active
view and forces a full server-history round trip. Adding SQL calls directly to
the large window would deepen the current UI/application/persistence coupling.

## Decision

- Add `LocalConversationRepository` as the Windows client's SQLite persistence
  adapter. `ChatWindow` may depend on this repository, while the repository has
  no UI or transport dependency.
- Place the database below `QStandardPaths::AppLocalDataLocation`, in an account
  directory named by SHA-256 rather than an unsanitized username. Rows retain an
  account key as an additional isolation boundary.
- Use an explicit schema version (`PRAGMA user_version`), WAL, foreign keys, and
  transactional snapshot replacement. Refuse to open a schema newer than the
  client understands.
- Store room/direct conversation cursor and bounded draft state plus at most the
  newest 500 message metadata records. Do not store attachment bytes or inline
  image data; the existing bounded media cache remains the byte owner.
- Reconcile storage identities by server message ID, then `clientMessageId`, then
  sequence/local fallback. Replacing a snapshot removes locally stale deleted
  messages while preserving its draft.
- Keep repository access on its owning Qt thread for this first slice. Moving
  high-volume writes to a worker requires an explicit serialized command/result
  boundary rather than sharing a `QSqlDatabase` connection across threads.

## Alternatives Considered

- Put SQL in `ChatWindow`: rejected because it prevents the intended
  View/Application/Repository separation and is difficult to test.
- Reuse the server database/schema: rejected because client cache/outbox data has
  different ownership, retention, migration, and failure semantics.
- Store JSON files per conversation: rejected because atomic replacement,
  pruning, schema migration, and indexed future outbox queries would be weaker.
- Add a third-party ORM: rejected for this bounded schema because Qt SQL is
  already available and avoids another runtime dependency.

## Consequences

The Windows client gains a testable durable-data boundary and a forward path for
cached startup, drafts, and outbox commands. SQLite remains reconstructable
client state; the server is authoritative for IDs, membership, timestamps,
sequence, recall, and deletion.

Snapshot replacement is intentionally simple and bounded. Later M2 performance
evidence may justify incremental upserts or a background writer, but no scale
claim is made by this foundation.

## Migration and Rollback

Version 1 creates new local-only tables; there is no legacy client database to
backfill. Future changes must expand to a new `user_version`, migrate
transactionally, validate, and only then use new columns/tables.

Rollback removes repository integration. The unused local database may remain;
server history restores the view. A newer schema is never downgraded in place.

## Verification

- a Qt SQL unit test covers clean creation, restart, the 500-message and
  10,000-character draft bounds, account isolation, authoritative replacement,
  monotonic cursor persistence, pruning, and draft preservation;
- the standard Qt gate compiles the repository into the desktop client;
- subsequent integration slices must verify cached render before history,
  reconnect resume, deletion/recall persistence, offline restart, and native
  Windows Release behavior.
