# ADR-0335: Windows V2 Messaging Runtime Composition

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The Windows messaging codec, local store, offline application service,
ViewModel, and shared authenticated WSS were independently verified. Without a
single runtime owner, however, product code could initialize those layers in the
wrong order, use the V1 username as durable identity, refresh UI before a
transaction commits, or leave the socket authenticated after a codec failure.

## Decision

- Add a QObject product controller that observes the existing transport's
  authenticated session and uses the server-issued account/device/session UUIDs
  as the only messaging identity.
- Create one account-isolated SQLite repository, application service, and
  ViewModel after first successful authentication. Reuse durable repository and
  presentation state when the same account/device resumes; bind only a fresh
  in-memory codec session.
- Open a conversation cache-first, then request sequence history from its
  committed cursor. Refresh the ViewModel only after ACK, live event, or history
  application returns from the repository boundary.
- On ordinary disconnect, clear volatile protocol correlations while preserving
  durable pending intent. On invalid messaging data, report a safe failure and
  reject the authenticated transport so its existing resume/reconnect policy
  repairs from durable state.
- Keep repository construction injectable for isolated tests. Production uses
  the hashed account-specific `AppLocalDataLocation` path.

## Consequences

Windows now has one tested runtime composition path from authenticated WSS bytes
through durable state to reply-ready rows. It remains independent of the V1
Widgets chat state and does not infer a mapping from legacy integer room IDs or
usernames to V2 conversation UUIDs.

A user-visible Windows conversation directory and panel attachment are still
required before the final reply delivery item can be closed.

## Verification

The controller integration test authenticates through the actual Windows V2
transport, initializes an injected SQLite store, opens a conversation from
cursor zero, routes and commits a server history page, refreshes the ViewModel,
and abandons volatile state on stop. The target is part of the pinned protocol
binding gate with warnings treated as errors for project sources.

## Rollback

Remove the runtime controller and its product/test targets. The transport,
application service, local repository, and ViewModel remain detached and no
server, database, or wire migration is required.
