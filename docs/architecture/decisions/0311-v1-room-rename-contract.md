# ADR-0311: Convergent V1 Room Rename Contract

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3

## Context

The V1 `RENAME_ROOM_REQ` command currently mixes request parsing,
administrator authorization, SQLite mutation, process-local cache updates, and
live notifications. It has no client operation ID. The Java compatibility
module already projects the canonical PostgreSQL room title but cannot mutate
it, so retries after a lost response could otherwise produce repeated effects.

## Decision

- Add a transport-independent command with the authenticated actor account,
  positive mapped V1 room ID, and requested title. Request-body actor identity
  is never accepted.
- Strip leading/trailing Unicode whitespace, reject empty/control-containing
  titles, and cap the canonical title at 100 Unicode code points to match the
  PostgreSQL `VARCHAR(100)` product model.
- In one serializable transaction, require an enabled active OWNER/ADMIN in an
  open mapped GROUP, lock the conversation, compare the canonical title, and
  update only when it differs.
- Return the old title, normalized new title, authoritative update time, and a
  `changed` flag. Repeating a title already in place succeeds with
  `changed=false` and emits no second notification or system message.
- Emit the existing `RENAME_ROOM_RSP`, `RENAME_ROOM_NOTIFY`, and `SYSTEM_MSG`
  shapes only from the detached gateway adapter. Keep the product listener and
  C++ in-memory room cache unchanged until formal cutover gates are met.

## Consequences

The mutation is retry-convergent without inventing an operation key unsupported
by existing Web and Windows clients. PostgreSQL becomes the authority for the
room title in the detached Java path; no process-local title cache is added.

This contract does not provide an append-only rename audit. If moderation or
compliance later requires historical title attribution, add a reviewed durable
event rather than treating application logs as source of truth.

## Verification

- application tests cover whitespace normalization, Unicode length, controls,
  invalid room identity, and persistence identity drift;
- PostgreSQL tests must cover owner/admin authorization, inactive membership,
  first change, convergent retry, and durable directory projection;
- gateway tests must cover strict parsing, first-only room effects, malformed
  and saturated execution, and replacement-login recovery.
