# ADR-0277: Define the V1 Room Search Boundary

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define transport-independent V1 room search from a server-bound authenticated
actor and a trimmed, non-empty, control-free keyword of at most 256 UTF-8 bytes.
Return at most 20 GROUP conversations with positive signed-32-bit V1 room and
creator IDs, bounded room title, and non-negative active-member count. Canonical
account and conversation UUIDs are internal consistency identities and never
cross the result boundary.

The future PostgreSQL adapter may preserve V1's positive decimal keyword as an
exact room-ID lookup and otherwise use a literal case-insensitive title match.
It must resolve creator identity from canonical OWNER membership plus the V1
account mapping, count only active members, expose only complete ROOM mappings,
and order results deterministically. Missing identity or duplicate projections
fail the whole search rather than silently returning a partial authoritative
list.

This read-only slice adds no PostgreSQL adapter, JSON handler, membership
mutation, room-password behavior, or product route. Rollback removes the unused
application types.

## Verification

Application tests prove authenticated actor propagation, keyword normalization
and bounds, UUID-free projection, result limits, and duplicate fail-closed
behavior.
