# ADR-0381: Dual-Edge Failure-Domain Gate

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

Existing M5 gates make gateways redundant behind one HAProxy process, but that
edge remains a single failure domain. Gateway health, Redis routing, and durable
history cannot help a client whose only reachable TLS terminator has crashed.
Before designing DNS, anycast, or global load balancing, the data plane must
prove that a session can move between independent edge processes without losing
committed conversation order.

## Decision

- Extend the disposable HAProxy runtime controller only when an explicit
  multi-edge scenario is selected. Allocate two host ports, container names,
  configurations, and HAProxy master processes while sharing only read-only test
  TLS/CA material.
- For deterministic fault isolation, route the primary edge only to gateway A
  and the secondary edge only to gateway B. This is a test topology, not the
  production recommendation; production edges should normally reach the full
  healthy gateway pool.
- Connect one caught-up client through each edge and require sequence 1 to cross
  both the edge and gateway boundaries through PostgreSQL plus Redis routing.
- Force-kill only the primary HAProxy container. Require its WSS listener to
  terminate while both Java gateways remain ready.
- Explicitly reconnect the same durable device session to the secondary URL,
  repair sequence 1 from PostgreSQL, then deliver sequence 2 without a duplicate
  peer event.
- Keep the Docker/local-service gate explicit and outside ordinary `--all`.

## Consequences

The repository now has bounded evidence that the Java/Redis/PostgreSQL data plane
survives loss of one edge process and accepts a client on a distinct edge and
gateway. Edge failure no longer implies gateway readiness withdrawal.

The test performs explicit URL selection on one host. It does not prove DNS or
GSLB detection time, anycast convergence, automatic Web/Windows endpoint
selection, multi-host network partitions, production certificate distribution,
cross-region PostgreSQL/Redis availability, or reconnect-storm capacity.

## Verification

Run:

```bash
python3 tools/verify_m0.py --gateway-multi-edge
```

The same change must retain the existing single-edge reload gate because the
controller is shared.

## Rollback

Remove the optional secondary container/controller branch, wrapper, scenario,
and quality selector. All single-edge HAProxy and Java runtime behavior remains
unchanged; no protocol or persistent schema migration is involved.
