# ADR-0361: Default-Off Distributed Routing Configuration

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The gateway needs a configuration boundary before the Redis component graph can
be assembled. Reusing loosely parsed environment values would risk accidental
activation, plaintext production Redis, unbounded client queues, or credential
disclosure through configuration logging.

## Decision

- Keep distributed routing disabled unless
  `CHATROOM_GATEWAY_DISTRIBUTED_ROUTING_ENABLED=true` is parsed exactly.
- Require `CHATROOM_REDIS_ROUTING_URI` only when enabled.
- Reuse the Redis adapter policy requiring `rediss://` plus authentication for
  production. Permit `redis://` only for localhost/127.0.0.1 when the explicit
  `CHATROOM_REDIS_ALLOW_INSECURE_LOOPBACK_FOR_TESTS=true` flag is present.
- Bound command timeout to 100–10,000 ms and request queue size to 16–10,000,
  with conservative defaults of one second and 256 requests.
- Return no Redis configuration while disabled and redact user information from
  all string rendering.
- Promote `routing-redis` to an implementation dependency of `im-gateway`, but
  do not instantiate it or change product traffic in this step.

## Consequences

Future composition has one immutable, fail-closed activation policy. Operators
can run the existing gateway without Redis, and test environments must opt in
twice before plaintext loopback Redis is accepted.

The configuration does not yet create a Redis connection, start distributed
loops, change readiness, or register conversation routes.

## Verification

Unit tests cover the disabled default, missing endpoint, production TLS/auth,
secret redaction, explicit loopback test mode, strict booleans, and resource
bounds. Dependency locks record the promoted runtime closure.

## Rollback

Remove the inactive configuration and return `routing-redis` to a test-only
dependency. Product behavior remains the single-gateway path.
