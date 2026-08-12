# ADR-0257: Read Complete V1 Direct History from PostgreSQL

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0255, ADR-0256

## Decision

Implement the V1 direct-history port as one read-only PostgreSQL transaction at
`REPEATABLE READ`. Resolve the authenticated enabled account and exact mapped
peer to a canonical DIRECT conversation only while both memberships are active.
The adapter never accepts a client-supplied conversation or friendship ID.

Before returning any page, verify that the whole conversation is representable
by V1: every entry is a message or recall, every message is canonical UTF-8 type
1 with a positive FRIENDSHIP message mapping, preserved `text`/`emoji` type,
and mapped sender, and every recall has exactly one mapped target. Unknown entry
kinds, invalid UTF-8, partial mappings, or duplicate recall events fail the
whole snapshot rather than creating a silent synchronization gap.

Latest mode selects the newest bounded messages, optionally before an exclusive
database timestamp, then returns them in creation-sequence order. Sequence mode
orders by `max(message sequence, recall sequence)`, fetches one extra row to
derive `hasMore`, and folds recall into the original mapped message. A final or
empty page advances `nextSequence` to the repeatable-read high watermark so
canonical sequence gaps cannot stall reconnect synchronization. Cursors beyond
that watermark are rejected.

This slice does not activate a network route. Rollback removes the adapter; it
introduces no schema or wire-format change.

## Verification

Application compilation proves the port contract. Disposable PostgreSQL tests
cover text/emoji presentation, latest ordering, bounded forward pages, a recall
whose mutation order differs from creation order, final watermark advancement,
invalid cursors, unauthorized readers, and fail-closed missing presentation
metadata.
