# ADR-0296: Bound V1 room-settings reads to durable server authority

- Status: Accepted
- Date: 2026-08-13

## Context

Windows and Web request `ROOM_SETTINGS_REQ` when entering a room. The V1 message
also carries an administrator mutation when any of `maxFileSize`,
`totalFileSpace`, `maxFileCount`, or `maxMembers` is present. The Java gateway
does not yet own file cleanup or developer-key administration, so treating a
mutation-shaped request as a read would silently discard user intent.

The successful read response needs all four limits. Only `maxMembers` currently
has canonical PostgreSQL storage. Returning legacy defaults for the other
fields would overwrite imported product truth at the compatibility boundary.

## Decision

Add a read-only application use case bound to the authenticated account and the
numeric V1 room identity. Persistence must authorize an enabled account with an
active membership in an active mapped GROUP and return all four durable limits
from one consistent snapshot. Missing, invalid, or partially migrated settings
fail closed; the application layer does not synthesize defaults.

File sizes remain positive JSON-safe integers, total space is not smaller than
the per-file limit, file count is positive, and member count remains within the
existing 1..1,000,000 admission bound. The gateway will accept only the exact
read shape. A request containing any mutation field is not a read and remains
inactive until a separately designed administrator mutation, cleanup, audit,
and developer-key replacement path exists.

## Consequences

- The next persistence step must add canonical file-limit columns and import
  the complete verified V1 `room_settings` row before the handler is composed.
- Read authorization cannot be inferred from possession of a room ID.
- No UUID, internal conversation identity, or invented default crosses V1.
- Room-settings mutation and notification remain on the legacy owner for now.
