# ADR-0371: TLS Product Readiness and HAProxy Policy

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The gateway's readiness endpoint was deliberately bound to numeric loopback with
metrics and liveness. That is correct for the management plane, but a central
load balancer cannot actively inspect loopback endpoints on several gateway
hosts. Nginx OSS on the development host supplies only passive upstream failure
detection and therefore cannot satisfy the requirement to stop new WSS routing
when PostgreSQL or the distributed Redis lease becomes unready.

Allowing remote access to the entire admin listener would unnecessarily expose
metrics and expand its threat model. The smallest boundary is one fixed
readiness response on the already authenticated TLS product listener.

## Decision

- Add unauthenticated `GET` and `HEAD /health/ready` to the TLS product listener.
  Use exactly the same runtime/dependency supplier as the loopback admin route.
  Return only 200 `ready` or 503 `not_ready`, set `Cache-Control: no-store`, close
  the health connection, reject other methods, and treat supplier exceptions as
  unready.
- Install the handler after the existing Host and trusted-proxy policies and
  before the WebSocket endpoint policy. All unrelated requests continue through
  the unchanged WSS pipeline.
- Keep `/health/live`, `/metrics`, and the admin listener numeric-loopback only.
  Production network policy must allow backend product ports only from the edge
  and other explicitly owned internal callers.
- Add a strict HAProxy renderer. Bound the backend count to 64 and reject unsafe
  identifiers, hosts, ports, paths, duplicates, and output injection.
- Terminate public TLS at HAProxy and use a second TLS hop to each gateway.
  Require the backend CA and explicit hostname verification. Overwrite inbound
  forwarding headers, use least-connections, and actively check the fixed
  product readiness path with an allowed public Host.
- Pin the syntax gate to the official HAProxy 3.2 Alpine manifest digest. Keep
  Docker validation explicit rather than adding it to the ordinary `--all` gate.

## Consequences

A remote load balancer can remove an unready gateway without access to admin
metrics. The public-facing health response reveals only binary availability and
performs no identity, conversation, or message operation. PostgreSQL and Redis
readiness checks now receive edge polling load, so the one-second sample policy
must be reviewed with fleet size and database capacity.

The generated HAProxy file is a deployment input, not a service discovery
system. Operators still own secret injection, certificate rotation, atomic
reload, backend membership, termination grace, monitoring, and rollback.

## Verification

Handler tests cover dynamic GET, body-free HEAD, method rejection, no-store, an
unrelated request pass-through, and fail-closed supplier exceptions. The real
Redis outage scenario proves the TLS product path changes 200→503→200 while
authenticated WSS sessions remain connected and durable messaging converges.

Renderer tests cover policy output and hostile/bounded inputs. The pinned
HAProxy container accepts a two-backend configuration with verified TLS and
active product-port checks. Real proxy forwarding and health-driven backend
removal remain the next gate.

## Rollback

Remove the product readiness handler and HAProxy renderer, and continue using
the loopback admin endpoint with an orchestrator-local health agent. Product
rollback can also keep distributed routing disabled; no protocol or database
migration is involved.
