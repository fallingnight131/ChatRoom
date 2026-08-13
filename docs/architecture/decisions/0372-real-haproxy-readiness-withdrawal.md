# ADR-0372: Real HAProxy Readiness Withdrawal

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0371 defined a product-port readiness contract and a bounded HAProxy policy,
but its container gate only parsed configuration. It did not prove that real WSS
connections traverse the proxy, least-connections separates active sessions, or
an unready gateway stops receiving new sessions while an existing connection
finishes its bounded drain.

The development host is macOS. Docker Desktop containers do not necessarily
share the host network namespace, and `host.docker.internal` was not routable to
the Java listeners in the observed environment. The runtime gate therefore
needs an explicit, reproducible bridge without changing production topology.

## Decision

- Add an explicit high-cost `--gateway-load-balancer-runtime` gate. Keep it out
  of ordinary `--all` because it owns local PostgreSQL and Redis processes,
  generates temporary TLS material, starts two complete Java runtimes, and runs
  the digest-pinned HAProxy container.
- Bind only the two disposable gateway product listeners to all host interfaces
  for this test. Keep both admin/metrics listeners numeric-loopback. Discover the
  host's routed IPv4 address for HAProxy's backend targets; publish the HAProxy
  frontend only on host loopback.
- Require frontend TLS plus a second verified TLS hop. Send backend SNI matching
  the independently verified certificate hostname. Mount only the disposable
  frontend PEM, backend CA, and rendered configuration into HAProxy; do not
  expose gateway keys or temporary database files to the container.
- Establish two authenticated WSS sessions through HAProxy and use fixed-label
  per-gateway authentication metrics to prove least-connections placed them on
  different runtimes.
- Withdraw one runtime's readiness and listener admission while preserving its
  existing WSS session. After more than two HAProxy health intervals, require a
  replacement WSS session to land on the remaining gateway.
- Submit sequence 1 through the draining session, close it cooperatively, repair
  that message from PostgreSQL history on the replacement connection, then send
  sequence 2 through the remaining gateway. Require two durable messages, two
  published outbox rows, ordered remote delivery, and no duplicate peer event.

## Consequences

The gate proves real same-build HAProxy forwarding, active removal, connection
drain, reconnect placement, authoritative history repair, and sequence
continuity on the observed host. It also validates the deployment renderer's
frontend and backend TLS policy against live Java gateways.

The routed host address is a test transport accommodation, not a production
network recommendation. Local firewall or Docker networking policy must permit
the container to reach the two random gateway ports. PostgreSQL, Redis, and the
admin plane remain loopback-only.

This is not evidence for abrupt process death, forced drain timeout, HAProxy
reload, certificate rotation, mixed-version rollout, reconnect storm capacity,
or availability across hosts/availability zones. Those remain separate gates.

## Verification

Run:

```bash
python3 tools/verify_m0.py --gateway-load-balancer-runtime
```

The orchestrator removes all temporary certificates, data directories, local
processes, and the HAProxy container on success or failure. The test is skipped
by ordinary Gradle runs unless all required disposable-service variables are
provided by the orchestrator.

## Rollback

Remove the explicit runtime gate and backend SNI addition while retaining the
ADR-0371 product readiness endpoint and syntax gate. No protocol, database, or
production deployment migration is involved.
