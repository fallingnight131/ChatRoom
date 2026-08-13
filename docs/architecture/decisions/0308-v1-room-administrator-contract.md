# ADR-0308: Define the V1 room-administrator command boundary

- Status: Accepted
- Date: 2026-08-13

## Context

`SET_ADMIN_REQ` controls every later room-administration capability. The C++
handler combines unbounded JSON parsing, authorization, role mutation, local
routing, and user-list refresh. Moving that shape directly into the Java gateway
would make client fields authoritative and make owner protection difficult to
enforce consistently.

## Decision

Introduce a transport- and persistence-independent command boundary with a
server-bound actor account, positive V1 room ID, exact bounded target username,
and desired administrator state. The application service validates input and
rejects any persistence result whose room, target username, or desired state
differs from the command.

The atomic PostgreSQL adapter returns a canonical conversation and target
account plus a `changed` flag. Stable rejection categories distinguish invalid
input, missing administrator authority, attempts to demote another account,
inactive/non-member targets, and the protected canonical OWNER role.

Promotion remains compatible with existing clients: an active OWNER or ADMIN
may promote an active mapped member. Demotion is self-service for ADMIN only.
The canonical OWNER cannot be demoted through this compatibility command; owner
transfer remains an explicit lifecycle operation. Repeating an already reached
role is a successful `changed=false` convergence and must not emit another live
notification.

The adapter locks the active mapped room plus actor and target memberships in a
serializable transaction, uses compare-and-set role updates, touches the
conversation only on change, and retries bounded serialization/deadlock
failures. This ADR defines no wire handler or product-listener change; transport
composition is a subsequent independently verified slice.

## Consequences

- Authorization and owner invariants remain in the future serializable database
  transaction rather than in the gateway.
- Web and Windows retain their existing target-state command shape.
- The Java boundary can suppress duplicate live effects without requiring a new
  client operation field that V1 clients do not send.
