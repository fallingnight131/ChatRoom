# ADR-0309: Make V1 room kicks authorized and retry-convergent

- Status: Accepted
- Date: 2026-08-13

## Context

The C++ `KICK_USER_REQ` handler authorizes, mutates membership, and routes three
live effects without a durable operation marker. A response lost after the
membership write can cause a retry to repeat target/member notifications. The
same inactive membership shape also results from voluntary leave, so `left_at`
alone cannot prove a prior kick.

## Decision

Introduce a transport- and persistence-independent command with a server-bound
actor account, positive V1 room ID, and exact bounded target username. Only an
active mapped OWNER or ADMIN may kick an active mapped MEMBER. OWNER and ADMIN
targets are protected, including the actor.

A successful result carries canonical conversation and target identities, safe
room/target presentation, the durable kick instant, and `changed`. A first kick
returns `changed=true`; an exact retry by the same actor against the same
inactive membership generation returns the original result with
`changed=false`. Voluntary leave, a kick by another actor, and a prior kick
followed by rejoin are not exact retries.

V032 and the PostgreSQL adapter write an append-only administrative kick
record in the same serializable transaction that sets membership `left_at`.
Retry recognition must match conversation, actor, target, and the exact current
membership `left_at`. Rejoin clears `left_at`, creating a new active generation
without deleting audit history.

Only `changed=true` may later emit `KICK_USER_NOTIFY` to the target and
`USER_LEFT` to remaining active local members. Database commit remains
authoritative if routing fails. The detached strict handler now emits those
effects plus the compatible room system message, with bounded off-event-loop
work and fixed outcomes. Exact retry and business rejection never notify;
malformed, concurrent, saturated, and dependency-failed commands close
generically. The handler is not yet composed into the compatibility module or
product listener.

## Consequences

- Network retry cannot repeat a completed kick's live effects.
- Moderation authorization and protected-role checks stay in PostgreSQL rather
  than trusting client state.
- Durable audit distinguishes administrative removal from voluntary leave and
  survives reconnect or process restart.
- Disposable PostgreSQL verifies clean/restart migration, unauthorized actor,
  protected role, non-member target, first kick, exact retry, different-actor
  denial, rejoin, and a distinct second-generation audit event.
- Handler tests cover strict parsing, actor binding, first-only target/member
  effects, UUID-free responses, rejection, dependency failure, and saturation.
- Compatibility-module composition and the product listener remain subsequent
  independently verified slices.
