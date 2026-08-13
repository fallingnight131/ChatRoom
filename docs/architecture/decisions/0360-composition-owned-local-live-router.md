# ADR-0360: Composition-Owned Local Live Router

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

`V2GatewayServer` previously constructed its process-local conversation router
internally. Distributed hint repair must share that exact router because it
reauthorizes and publishes only to subscriptions created by the WebSocket
history path. A separately constructed repair router would silently consume
Redis hints while seeing no product subscriptions.

## Decision

- Create the `SingleGatewayConversationLiveRouter` in `GatewayRuntime` with the
  runtime clock.
- Inject that instance into `V2GatewayServer` through a package-scoped complete
  composition constructor.
- Preserve existing public and test constructors by giving them an isolated
  default router, so this ownership move does not change their API or behavior.
- Do not activate distributed routing in this change.

## Consequences

The Java composition root can now pass one router to both the product WebSocket
pipeline and the future PostgreSQL-backed Redis hint repair adapter. The server
still encapsulates transport use of the router; callers do not receive a new
mutable getter.

## Verification

Gateway listener and runtime lifecycle tests compile and pass through both the
existing default constructor path and the new composition-owned path. The full
backend check remains warning-clean.

## Rollback

Restore internal router construction in `V2GatewayServer`. Distributed hint
repair must not then be composed because it cannot share product subscription
state.
