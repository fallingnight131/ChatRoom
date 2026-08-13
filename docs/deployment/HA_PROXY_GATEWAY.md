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

The generated backend uses least-connections, verifies the backend CA and
hostname, and performs `GET /health/ready` over the same TLS product port with
the approved Host. Two consecutive failed checks remove a gateway; two
successful checks restore it. WSS connections remain bound to their selected
gateway, so cookie stickiness is unnecessary. Five-minute tunnel inactivity is
longer than the gateway heartbeat interval but remains bounded.

## Validate and operate

```bash
python3 tools/verify_m0.py --gateway-load-balancer-config
```

The local gate runs injection/bounds tests, generates disposable certificates,
renders two servers, and syntax-checks the result with the official HAProxy 3.2
Alpine image pinned by manifest digest. It does not contact a real gateway.

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
readiness removal, WSS forwarding, certificate rotation, reload safety, or
mixed-version compatibility; those require separate runtime gates.
