# ADR-0008: Baseline Critical V1 SQLite Query Plans

- Status: Accepted
- Date: 2026-07-11
- Owners: project maintainers
- Related milestone: M1

## Context

V1 had only timestamp indexes for room and direct history. Reconnect loads rooms
by `room_members.user_id`, unread counts filter by conversation plus message ID,
file quotas scan active room files, friend requests filter by recipient/pair and
status, and normalized friendships can be found through `user_id2`. SQLite's
automatic primary-key/unique indexes do not cover these leading-column patterns.

The M1 authorization changes also add relationship joins to hot paths. Merely
declaring indexes is insufficient; a regression must prove that SQLite's planner
selects them for the application query shapes.

## Decision

Add idempotent startup indexes for:

- `room_members(user_id, room_id)`;
- `messages(room_id, id)`;
- `friend_messages(friendship_id, id)`;
- `files(room_id, cleared, created_at, id)`;
- `friend_requests(to_user_id, status, created_at)`;
- `friend_requests(from_user_id, to_user_id, status)`; and
- `friendships(user_id2)`.

Keep the existing room/direct timestamp indexes for history pagination. Extend
the clean-start/restart schema regression with `EXPLAIN QUERY PLAN` assertions
that name each intended index. The test fails when a future query/index change
causes a table scan or selects an unintended access path.

## Consequences

Database initialization creates seven additional indexes on existing data. This
increases first deployment time, database size, and write amplification, but it
removes full scans from reconnect, unread, active-file, and contact-request
paths. The current startup migration is not versioned and cannot build indexes
concurrently, so operators must back up and measure a production-sized copy
before rollout.

An older server can safely run with these extra indexes; rollback does not
require dropping them. If storage/write amplification is unacceptable, drop a
specific new index only after restoring a query-plan test and benchmark for the
replacement design.

## Verification

`python3 tools/verify_m0.py --db-schema` creates a fresh database, checks all
seven query plans, runs integrity checking, initializes the database again, and
requires identical schema snapshots. The machine-readable inventory records
nine explicit indexes in total.
