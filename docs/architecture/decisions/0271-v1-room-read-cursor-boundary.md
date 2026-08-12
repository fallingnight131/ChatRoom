# ADR-0271: Define V1 Room Read-Cursor Advancement

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define transport-independent V1 room-read intent from a server-bound
authenticated actor and positive signed-32-bit V1 room ID. Do not accept a
client-selected account, message ID, sequence, timestamp, or read-receipt
audience.

A future PostgreSQL adapter must resolve an enabled mapped actor's active GROUP
membership and ROOM mapping, observe the conversation high watermark in the
same transaction, and monotonically advance that member's canonical
`last_read_sequence`. Return the previous/resulting sequences and whether the
cursor changed for fixed telemetry and verification. Missing room and inactive
membership are one opaque access denial.

Use canonical conversation sequence rather than V1 database message ID as the
ordering authority. Runtime V1 message IDs now allocate downward and recalls or
deletion events share the sequence namespace, so numeric message-ID comparison
cannot safely represent progress. Advancing across non-message mutation/event
sequences is valid: the room unread projection counts durable message creation
after the cursor and the user has observed the room snapshot at mark time.

Preserve V1 behavior by emitting no room read receipt. This slice adds no
PostgreSQL adapter, handler, or product-listener activation. Rollback removes
the unused boundary and changes no durable data.

## Verification

Application tests prove authenticated actor propagation, positive signed-32-bit
room validation, exact mapped identity, monotonic result invariants, changed
flag consistency, and rejection of persistence identity substitution.
