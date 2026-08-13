# ADR-0376: HAProxy Master-Worker Reload Gate

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The edge configuration is generated atomically, but production backend
membership, TLS policy, and certificates eventually require HAProxy reloads.
ADR-0372 proves active health withdrawal without changing the running edge
configuration. It does not prove that the master-worker reload mechanism keeps
established WSS tunnels alive or that new sessions use only the replacement
backend set.

Restarting the edge process would disconnect every desktop and Web client at
once. A reload that silently leaves old workers accepting new sessions would
also defeat a rollout or emergency backend removal.

## Decision

- Give the disposable HAProxy container a bounded unique name and retain
  master-worker mode. Cleanup force-removes that exact name on every exit path.
- Extend the local orchestrator with one strict reload request: atomically render
  a new configuration containing exactly one of the two already validated
  gateways, then send `SIGUSR2` to the named HAProxy master.
- Start two authenticated, history-synchronized WSS sessions on different
  gateways. Remove the sender's gateway from the new configuration while
  preserving its established tunnel on the former worker.
- After reload, require a new authenticated session to land on the sole retained
  gateway. Submit and remotely deliver sequence 1 through the old worker/tunnel,
  repair it through authoritative history on the new connection, then submit
  and deliver sequence 2 through the new worker without a duplicate peer event.
- Keep this Docker/local-service gate explicit and outside ordinary `--all`.

## Consequences

The same-build edge topology now proves atomic backend-set reload without a
fleet-wide WSS disconnect. Old workers stop admission and finish their existing
tunnels; new sessions obey the newly rendered backend set. PostgreSQL remains
the durable sequence authority across the worker boundary.

This gate uses the same frontend and backend certificates before and after
reload. It does not prove certificate rotation, malformed-config rollback,
concurrent reload serialization, mixed HAProxy versions, or a long-lived tunnel
soak. Production automation must syntax-check first, serialize reloads, retain
the last known-good configuration, and alert on former-worker drain time.

## Verification

Run:

```bash
python3 tools/verify_m0.py --gateway-load-balancer-reload
```

The orchestrator owns and removes HAProxy, PostgreSQL, Redis, gateway runtimes,
certificates, and control files on success or failure.

## Rollback

Remove the reload control path and explicit scenario. Continue using the
ADR-0371 syntax gate and controlled HAProxy restarts during a maintenance
window. No protocol, schema, or Java runtime behavior changes.
