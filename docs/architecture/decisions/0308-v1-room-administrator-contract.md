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
failures.

The detached Netty handler now owns strict bounded JSON parsing, authenticated
actor binding, one in-flight command per connection, and bounded off-event-loop
execution. It emits compatible `SET_ADMIN_RSP`; only a committed
`changed=true` result may route `ADMIN_STATUS` to the active local target.
Stable business rejection keeps the connection usable, while malformed,
concurrent, saturated, or failed commands close generically. The handler and
PostgreSQL adapter are now composed in the detached compatibility module. Real
PostgreSQL verification covers login, first promotion, durable role state,
target notification, convergent retry suppression, member-directory refresh,
and replacement-login role recovery. The product listener remains unchanged.

## Consequences

- Authorization and owner invariants remain in the serializable database
  transaction rather than in the gateway.
- Web and Windows retain their existing target-state command shape.
- The Java boundary can suppress duplicate live effects without requiring a new
  client operation field that V1 clients do not send.
