# ADR-0252: Allocate Runtime V1 Friend-Message IDs Downward

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Add a dedicated PostgreSQL sequence for runtime-created V1 friend-message IDs.
Allocate downward from signed 32-bit maximum while imported SQLite message IDs
retain their historical positive values. The future submission adapter must
check `legacy_v1_message_map` occupancy for kind `FRIENDSHIP`, skip collisions,
and insert the selected mapping in the same transaction as the canonical
message. Sequence gaps after rollback are harmless and IDs are never reused.

Keep this allocator separate from room messages, friendships, and contact
requests. A sequence value alone is not an accepted message and never crosses
the compatibility boundary without a committed mapping. This migration is
additive and rollback uses database restore rather than a down migration.

## Verification

The clean/restart migration test requires the current V019 history and verifies
the sequence namespace, direction, and signed 32-bit bounds against real
PostgreSQL. Collision skipping and atomic mapping remain required in the next
adapter slice.
