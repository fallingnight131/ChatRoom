# ADR-0290: Define Durable V1 Room Leave Semantics

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Follows: ADR-0289

## Decision

Define a transport-independent V1 room-leave boundary whose actor comes only
from authenticated server state and whose only client input is a positive
32-bit legacy room ID. Persistence must decide membership removal, ownership
succession, and last-member dissolution atomically.

An exact retry after a committed leave is a successful `newLeave:false` result
when the actor has a retained inactive membership. A never-member is rejected
as `NOT_MEMBER`; missing rooms and disabled or unmapped actors remain distinct
fail-closed outcomes. Only `newLeave:true` may produce presence or ownership
notifications.

When the active owner leaves a non-empty room, persistence must promote exactly
one remaining mapped active member to owner in the same transaction. Selection
is deterministic: existing administrators first, then members, ordered by
earliest join time and canonical account ID. The result carries only the
internal successor account ID and bounded display projection needed for local
V1 routing; canonical IDs never cross the V1 wire.

The last active member leaving dissolves the room instead of physically deleting
the conversation graph. Durable messages, attachment metadata, compatibility
identities, and audit evidence must remain referentially intact. Dissolved rooms
must be excluded from search, join, directory, message, and administration
authorization. A later schema step will add the explicit lifecycle marker and
update every affected PostgreSQL adapter atomically before the handler can be
activated.

This deliberately replaces the C++ server's random administrator selection and
physical last-member deletion. Random succession is irreproducible under retry;
physical deletion conflicts with the target durable history and attachment
lifecycle. Rollback before handler activation removes the unused application
boundary and has no data effect.

## Verification

Application tests prove authenticated actor/room binding, invalid-ID rejection
before persistence, stable business outcomes, identity-substitution closure,
and rejection of impossible duplicate/dissolved/self-succession results.
