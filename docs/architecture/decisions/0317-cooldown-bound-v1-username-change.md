# ADR-0317: Cooldown-Bound V1 Username Change

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3

## Context

V1 calls the mutable login name a UID and sends `CHANGE_UID_REQ`. It is not the
stable numeric V1 user ID or canonical account UUID. Changing this natural key
affects subsequent login, in-memory authenticated identity, room projections,
and other users' cached sender keys. The current server permits one change per
30 days and broadcasts `UID_CHANGE_NOTIFY` to joined rooms except the actor.

## Decision

- Keep the stable numeric/UUID identities unchanged. Accept only trimmed ASCII
  `[A-Za-z0-9_]{6,20}` names and bind the actor to its authenticated account.
- In one serializable PostgreSQL transaction, lock the enabled mapped account,
  enforce global exact-name uniqueness and a database-time 30-day cooldown,
  update `account.username_key`, and append an old/new audit row.
- Distinguish an unchanged registration-era name from a retry: same-as-current
  is rejected unless the latest durable username-change audit proves that the
  requested name was the last committed destination. A proven exact retry
  returns success with `changed=false` and no notification intent.
- Capture complete active mapped room peer audiences as post-commit effect
  intent. Exclude the actor to preserve current V1 behavior and fail closed on
  an incomplete active-member mapping.
- After commit, refresh the connection identity and route compatible
  `UID_CHANGE_NOTIFY` effects. Subsequent login accepts only the new name.

## Consequences

The login name remains mutable compatibility data rather than account identity.
Lost responses are safely retryable without bypassing uniqueness or cooldown.
Clients with cached old sender keys receive best-effort live room effects and
recover durable directory state after reconnect. Restoration of an old name is
still a new mutation and remains subject to uniqueness and cooldown.

Rollback may ignore the new audit/cooldown columns while reading the current
username, but reverting a committed username requires an explicit audited
forward mutation or database restore, not a binary rollback.

## Verification

- application tests cover policy bounds, normalization, persistence drift, and
  retry/peer-effect invariants;
- PostgreSQL tests cover uniqueness races, cooldown, first-change audit, exact
  retry, complete peer audiences, rollback, and new-name login projection;
- gateway tests cover strict decoding, identity refresh, compatible response and
  notification shape, no-op retry, bounded execution, and saturation.
