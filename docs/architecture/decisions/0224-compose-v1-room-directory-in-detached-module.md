# ADR-0224: Compose V1 Rooms in the Detached Java Module

- Status: Accepted
- Date: 2026-08-13
- Owners: Java gateway, persistence, and protocol compatibility
- Related milestone: M3

## Context

ADR-0223 left the verified room-list handler uncomposed. The next reversible
boundary is to prove that an imported account can authenticate and consume its
real PostgreSQL canonical room directory through the complete detached pipeline,
without adding the incomplete V1 route to `GatewayRuntime` or a product listener.

## Decision

- Construct the room projection from `PostgresConversationDirectoryAdapter` and
  `PostgresLegacyV1ConversationProjection` inside `V1CompatibilityModule`.
- Install the room handler after login and heartbeat, with a separately injected
  directory executor and fixed event sink. Authentication and directory work do
  not have to share capacity in a future runtime.
- Extend the disposable PostgreSQL integration gate with one imported member
  room and one unrelated room. After real compatible authentication, require
  the existing numeric ID/name, administrator role, and sequence-derived unread
  result; reject unrelated membership and canonical UUID exposure.
- Keep `/v1/web` absent from `GatewayRuntime`. Login plus room listing is not a
  complete supported-client backend.

## Alternatives Considered

- Activate the route immediately: rejected because friend list, history,
  avatars, membership details, reads, and message commands remain incomplete.
- Test only mocked ports: rejected because SQL membership filtering and imported
  mapping joins are critical authorization behavior.
- Reuse the authentication worker as a permanent design: rejected because slow
  directory queries must not consume password/session capacity.

## Consequences

The detached Java pipeline now proves its first post-login read against real
PostgreSQL. It remains test-only composition and makes no performance, capacity,
or product-availability claim.

## Migration and Rollback

No schema or listener changes occur. Removing the module wiring and integration
fixture returns to ADR-0223 while retaining the tested application/handler
building blocks.

## Verification

- `./gradlew :im-gateway:test --no-daemon`
- `python3 tools/verify_m0.py --postgres`
- `python3 tools/verify_m0.py --java`
