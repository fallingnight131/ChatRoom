# ADR-0366: Disposable Redis TLS and Scoped-ACL Gate

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The default-off distributed gateway graph requires authenticated `rediss://`
configuration, but its previous real Redis tests used an explicitly permitted
plaintext loopback endpoint. Configuration validation alone could not prove that
Lettuce trusted the intended CA, rejected a certificate for the wrong host,
authenticated through a non-default Redis user, or operated with a constrained
key and command policy. Activating the preview without that evidence would make
TLS and least privilege deployment assumptions rather than tested capabilities.

The gate must be repeatable on a developer or CI host, must not depend on a
shared Redis service, and must not print or retain even disposable credentials.

## Decision

- Add `tools/verify_redis_tls.py` and expose it through the explicit
  `python3 tools/verify_m0.py --redis-tls` gate. Do not include it in `--all`,
  because it requires local Redis and certificate binaries beyond the ordinary
  repository build.
- Generate a one-day disposable CA and server certificate with only
  `IP:127.0.0.1` in the subject alternative name. Import that CA into a temporary
  PKCS12 trust store used only by the isolated Gradle test JVM.
- Start Redis with its plaintext port disabled, bind the TLS port to a random
  loopback port, disable persistence, and remove the process and temporary
  material on both success and failure.
- Disable the Redis default user. Give the `chat` user access only to
  `chat:v2:*` and the commands used by the routing adapter:
  `PING`, `SELECT`, `SET`, `GET`, `DEL`, `EVAL`, `ZADD`, `ZREM`,
  `ZRANGEBYSCORE`, `XADD`, and `XREAD`. Allow `XLEN` only for the capability
  assertion; do not grant `FLUSHDB` or broad key access.
- Prove real lease, route, bounded Stream, expiry, and reconnect behavior over
  TLS. Also require an outside-key write, a wrong ACL password, and a
  `localhost` connection to the IP-only certificate to fail.
- Keep credentials in the child environment rather than the command line,
  redact them from command/error text, and disable Gradle configuration caching
  for this invocation so temporary connection material is not serialized.

## Consequences

The selected Lettuce adapter now has reproducible evidence that its runtime
operations fit a scoped Redis identity and that its production TLS validation
fails closed. The product graph remains default-off, so this gate does not
change live traffic or make Redis durable truth.

`XLEN` is a read-only test allowance and may be omitted from the production
identity. Actual deployment still needs secret custody and rotation, managed
Redis certificate and ACL policy, alerting, and an operations runbook. Redis
dependency loss/recovery, load-balancer withdrawal, and rolling multi-gateway
behavior remain separate M5 gates.

## Verification

`python3 tools/verify_m0.py --redis-tls` creates the disposable topology and
runs `:routing-redis:test --rerun-tasks`. The positive path performs actual Lua
route publication and bounded Stream reads over TLS. Negative assertions prove
key-scope denial, authentication rejection, certificate hostname rejection, and
credential-free exceptions. PID-based cleanup waits for the owned Redis process
to exit and escalates to termination if necessary.

The ordinary Backend `check` task continues to run these integration tests as
skipped tests when no explicit disposable endpoints are supplied.

## Rollback

Remove the explicit gate and its test-only trust-store environment wiring. Keep
`CHATROOM_GATEWAY_DISTRIBUTED_ROUTING_ENABLED` false; the single-gateway local
router remains the product default and constructs no Redis resource.
