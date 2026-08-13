# ADR-0375: Forced Gateway Drain Timeout Gate

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The runtime withdraws readiness, stops listener admission, and waits a bounded
period for established channels to close voluntarily. Unit tests cover the
server primitive and ADR-0372 proves a cooperative client closes inside the
window. Neither proves that a non-cooperative authenticated WSS tunnel is
forcibly terminated after the configured timeout while HAProxy continues to
route new sessions to another gateway.

An unbounded drain can stall deployments and leave old application code serving
indefinitely. Forcing too early can create avoidable reconnect churn. The real
composition needs an end-to-end timing and placement gate.

## Decision

- Add an explicit `--gateway-forced-drain` local-service gate using two complete
  Java runtimes behind the digest-pinned real HAProxy edge.
- Configure a one-second drain timeout only for the disposable scenario. Place
  one authenticated WSS session on each gateway through least-connections.
- Start closing the gateway that owns the selected session. Require its product
  listener to stop accepting before forced cleanup, its close operation to take
  at least 900 ms but less than three seconds, and the old client connection to
  receive a terminal callback.
- Wait through more than two HAProxy health intervals, reconnect the same durable
  session through the public WSS endpoint, and require it to land on the
  surviving gateway with exact account/device/session identity and a rotated
  resume token.
- Keep the unaffected peer connected throughout. Ordinary production retains
  the documented 15-second default; this gate does not change that policy.

## Consequences

The release topology now proves both drain branches: cooperative completion and
bounded forced termination. Operators can set termination grace greater than
the application drain plus observed health propagation without risking an
indefinite old process.

The one-second value is only a fast test control. It is not a recommended
production drain duration, and this scenario does not measure a mass forced
disconnect, reconnect capacity, mixed-version compatibility, or load-balancer
reload behavior.

## Verification

Run:

```bash
python3 tools/verify_m0.py --gateway-forced-drain
```

The gate is excluded from ordinary `--all` because it owns Docker and disposable
PostgreSQL, Redis, certificates, and gateway processes.

## Rollback

Remove the explicit scenario and verifier while retaining the existing bounded
runtime drain, cooperative HAProxy gate, and 15-second production default. No
protocol, schema, or deployment migration is involved.
