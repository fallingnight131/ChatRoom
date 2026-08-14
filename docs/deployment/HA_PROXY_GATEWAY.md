# HAProxy Java Gateway Edge

This is the reviewed M5 configuration contract for terminating public WSS at
HAProxy and forwarding to the Java gateways over a second verified TLS hop. It
does not authorize production activation by itself.

## Prerequisites

- HAProxy 3.2 from a pinned, supported package or image.
- One frontend PEM containing the public certificate chain and private key,
  supplied from the deployment secret store.
- A CA bundle that validates every Java gateway product certificate.
- Every gateway certificate contains the DNS name passed as `verifyHost`.
- `CHATROOM_GATEWAY_ALLOWED_HOSTS` includes the public health/WebSocket Host.
- `CHATROOM_GATEWAY_TRUSTED_PROXY_CIDRS` contains only the HAProxy network when
  forwarded client addresses are required. HAProxy overwrites, rather than
  appends to, inbound forwarding headers.
- Network policy permits HAProxy to reach gateway product TLS ports and blocks
  untrusted direct access. The admin listener remains numeric loopback only.

## Render

Each `--gateway` value is
`name,host,productPort,verifyHost`. Names are bounded HAProxy identifiers; hosts,
ports, paths, and verification names are validated before output.

```bash
python3 tools/render_haproxy_gateway.py \
  --bind-address 0.0.0.0 \
  --bind-port 443 \
  --frontend-certificate /run/secrets/chat-edge.pem \
  --backend-ca /run/secrets/chat-gateway-ca.pem \
  --health-host chat.example.com \
  --gateway gateway-a,10.0.10.11,9443,gateway-a.internal \
  --gateway gateway-b,10.0.10.12,9443,gateway-b.internal \
  --output /etc/haproxy/haproxy.cfg
```

The generated backend uses least-connections, sends the verified backend name as
SNI, verifies the backend CA and hostname, and performs `GET /health/ready` over
the same TLS product port with the approved Host. Two consecutive failed checks
remove a gateway; two successful checks restore it. WSS connections remain bound to their selected
gateway, so cookie stickiness is unnecessary. Five-minute tunnel inactivity is
longer than the gateway heartbeat interval but remains bounded.

## Validate and operate

```bash
python3 tools/verify_m0.py --gateway-load-balancer-config
```

The local gate runs injection/bounds tests, generates disposable certificates,
renders two servers, and syntax-checks the result with the official HAProxy 3.2
Alpine image pinned by manifest digest. It does not contact a real gateway.

Run the separate local-service runtime gate before changing gateway removal or
drain policy:

```bash
python3 tools/verify_m0.py --gateway-load-balancer-runtime
```

It starts disposable PostgreSQL and Redis, two complete TLS Java gateways, and
the same pinned HAProxy image. It proves real WSS least-connections placement,
active readiness withdrawal, routing new sessions away from a draining gateway,
delivery through the old session during its drain window, reconnect history
repair, ordered follow-up delivery, and durable outbox convergence. On Docker
Desktop the container reaches only the two disposable all-interface gateway
ports through the host's routed address; the HAProxy frontend and every admin or
data dependency remain host-loopback. Local firewall policy must permit that
test path (ADR-0372).

Exercise ungraceful process loss separately:

```bash
python3 tools/verify_m0.py --gateway-crash
```

This variant runs each gateway in its own JVM, force-kills the gateway holding
one client without a shutdown hook, and requires HAProxy removal, Redis route
expiry, survivor placement, PostgreSQL history repair, and ordered delivery to
converge. It proves a single same-host process loss, not correlated host loss or
reconnect-storm capacity (ADR-0373).

Verify the forced end of the drain window with:

```bash
python3 tools/verify_m0.py --gateway-forced-drain
```

This holds an authenticated WSS session open past a disposable one-second drain,
requires listener withdrawal before bounded forced closure, then resumes that
session through HAProxy on the survivor. Production termination grace must still
exceed the configured application drain plus health-check propagation
(ADR-0375).

Verify master-worker reload separately:

```bash
python3 tools/verify_m0.py --gateway-load-balancer-reload
```

The gate atomically reduces the backend set, signals `SIGUSR2`, proves the former
worker stops admission but completes an established WSS message, and routes a
new session plus its next ordered message through the retained gateway. In
production, validate syntax first, serialize reloads, retain the last known-good
file, and monitor former-worker drain time (ADR-0376).

Verify frontend certificate replacement with:

```bash
python3 tools/verify_m0.py --gateway-load-balancer-certificate-rotation
```

This requires exact old/new leaf fingerprints through real HTTPS, preserves an
established old-worker WSS tunnel, and routes new WSS traffic through the new
certificate worker. Production must source PEMs from the secret store, validate
the full public chain and hostname, retain rollback material, and monitor expiry.
Backend gateway CA rotation is not covered (ADR-0377).

Verify private backend CA replacement with:

```bash
python3 tools/verify_m0.py --gateway-backend-ca-rotation
```

The gate enforces the safe order: install an old-plus-new verifier bundle,
migrate gateway certificates while both roots are trusted, prove both paths,
then contract HAProxy to the new root and reject the old certificate. It
preserves established former-worker WSS traffic across both reloads and never
disables hostname or CA verification (ADR-0378).

For production, distribute the overlap bundle to every edge before presenting a
new gateway certificate. Abort contraction while any healthy or rollback
gateway still uses the old certificate. Retain the overlap bundle until the
rollback window closes; CA key deletion and compromise response require a
separate, reviewed ceremony.

Verify the edge as an independent failure domain with:

```bash
python3 tools/verify_m0.py --gateway-multi-edge
```

This disposable gate uses two independent HAProxy processes and explicit
primary/secondary client URLs. Production should place at least two edge
instances in distinct failure domains, let each reach the reviewed healthy
gateway pool, distribute identical certificate generations through the secret
store, and remove an unhealthy edge from discovery before its reconnect load
overwhelms the survivor. The gate does not select or authorize a DNS, GSLB,
anycast, or certificate-distribution product (ADR-0381).

For a bounded secondary-edge concentration curve, add
`--gateway-multi-edge-output <path>`. The scenario keeps six sessions on the
secondary edge and resumes twelve primary-edge sessions in four controlled
batches after primary HAProxy is force-killed. Treat the JSON only as an
exact-revision local comparison; it does not size a production edge or gateway.
Release comparison requires clean-tree validation with
`tools/multi_edge_reconnect_result.py --require-clean` (ADR-0384).
New measurements use schema version 2 and include five-millisecond, in-window
authentication active-worker and queue-peak sampling (ADR-0386). Schema version
1 remains supported only for already-recorded historical evidence.
Schema version 6 adds the fixed `step-12`, `step-24`, and `step-48` comparison
profiles selected with `--gateway-multi-edge-workload`. Each keeps four batches
and six survivor sessions; arbitrary counts are intentionally rejected. A
single profile result is not a production capacity or pressure-onset result
(ADR-0393).

Before reload, validate the fully rendered deployment file with the exact
production HAProxy binary. Roll one bounded subset of gateways at a time:

1. verify the remaining capacity and healthy backend count;
2. make the selected gateway unready before termination;
3. wait for HAProxy removal and the application drain window;
4. start the replacement and wait for consecutive successful health checks;
5. observe reconnect, outbox, Redis lease/hint, PostgreSQL, and error signals;
6. abort the rollout if sequence repair, duplicate, backlog, or saturation
   signals exceed the reviewed release envelope.

The termination grace must exceed `CHATROOM_GATEWAY_DRAIN_TIMEOUT_SECONDS` plus
observed health-check propagation. A syntax-valid file is not evidence of
certificate rotation, reload safety, mixed-version compatibility, abrupt crash
recovery, or reconnect-storm capacity; those require separate runtime gates.
