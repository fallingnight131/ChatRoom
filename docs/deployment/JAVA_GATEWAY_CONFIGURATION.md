# Java V2 Gateway Configuration

Status: M3 runnable pre-cutover contract. Java V2 still receives no product traffic.

`GatewayRuntimeConfig` reads environment values once and validates them before
the WSS component binds. `GatewayMain` now composes the validated database,
identity, worker, admin, and product-listener lifecycle. Do not commit a
populated environment file, passwords, private
keys, production endpoints, or certificate material.

## Required values

| Variable | Meaning |
| --- | --- |
| `CHATROOM_GATEWAY_TLS_CERTIFICATE` | readable PEM certificate-chain file |
| `CHATROOM_GATEWAY_TLS_PRIVATE_KEY` | different readable PEM private-key file |
| `CHATROOM_GATEWAY_ALLOWED_HOSTS` | comma-separated exact TLS authorities |
| `CHATROOM_GATEWAY_WEB_ORIGINS` | comma-separated exact HTTPS browser origins |
| `CHATROOM_POSTGRES_URL` | `jdbc:postgresql://...?...sslmode=verify-full` URL |
| `CHATROOM_POSTGRES_USER` | dedicated least-privilege gateway role |
| `CHATROOM_POSTGRES_PASSWORD` | non-empty password supplied by secret storage |

`CHATROOM_GATEWAY_TLS_PRIVATE_KEY_PASSWORD` is optional. Its absence means the
PEM key must be usable without an application password and protected by host
filesystem permissions and secret delivery controls.

## Release identity

Production deployments set both values below from the immutable build pipeline.
Supplying only one or using a mutable tag/reformatted revision fails before
listener bind.

| Variable | Contract |
| --- | --- |
| `CHATROOM_GATEWAY_RELEASE_VERSION` | canonical SemVer without build metadata, for example `1.4.0` or `1.4.0-rc.1` |
| `CHATROOM_GATEWAY_SOURCE_REVISION` | exact lowercase 40-hex Git revision loaded by the artifact |
| `CHATROOM_GATEWAY_COMPATIBILITY_EPOCH` | optional integer `1..1000000`, default `1`; change only through ADR |

Local runs that omit both identity values report `development` and `unknown`.
The loopback-only exact `GET /identity` endpoint returns those fields together
with the non-overridable runtime V2 protocol version as deterministic JSON. It
is deployment evidence, not proof of cross-version compatibility; mixed-version
rollout still requires an explicit compatibility gate (ADR-0379).

## Detached V1 room-creation secret

`CHATROOM_V1_ROOM_PASSWORD_HMAC_KEY_BASE64` is required only when composing the
detached V1 compatibility module; `GatewayRuntimeConfig` and the current product
listener do not read it. It must be canonical padded Base64 for exactly 32
random bytes.

The key has no default and must come from secret-manager
injection rather than a command-line argument or committed environment file.
Keep it stable while V023 room-creation idempotency records exist. Rotation
requires a future versioned multi-key migration; changing it directly makes
same-password retries conflict. The product listener does not consume this key
yet because the V1 compatibility route remains detached.

## Inactive attachment object-storage values

The isolated `object-storage-s3` module validates the following shared private-
store non-secret values for attachments and profile images, but
`GatewayRuntimeConfig` and `GatewayMain` do not read them yet. Setting them does
not enable uploads or make a provider request.

| Variable | Meaning |
| --- | --- |
| `CHATROOM_ATTACHMENT_S3_ENDPOINT` | explicit HTTPS origin; no user info, query, fragment, or path |
| `CHATROOM_ATTACHMENT_S3_REGION` | bounded S3 signing region |
| `CHATROOM_ATTACHMENT_S3_BUCKET` | private attachment/profile-image bucket |
| `CHATROOM_ATTACHMENT_S3_PATH_STYLE` | optional exact `true` or `false`; defaults to `false` |

Credentials are deliberately not parsed into this configuration. A future
composition root must inject a reviewed AWS credential provider supplied by the
deployment platform. Do not enable ambient developer-profile fallback in
production. Before composition, the real bucket must pass the create-only,
SHA-256 HEAD, expiry, Web CORS, and cleanup acceptance described by ADR-0099.
The guarded operator probe and its additional explicit confirmation values are
documented in `ATTACHMENT_OBJECT_STORAGE_ACCEPTANCE.md`. It may deliberately use
the standard credential chain for a temporary non-production test identity;
that exception does not change the production composition rule.

The separate profile-image PUT/retry/GET/DELETE probe reuses these inactive
values and is documented in `PROFILE_IMAGE_OBJECT_STORAGE_ACCEPTANCE.md`. Its
explicit destructive confirmation is independent from the attachment/CORS
probe, and neither command is run by ordinary builds or CI.

The offline historical profile-image apply command may use the same values only
after the dated probe and independent no-object-remains review. It additionally
requires all three exact operator-only values below; none is read by the gateway:

| Variable | Required exact value |
| --- | --- |
| `CHATROOM_PROFILE_IMAGE_IMPORT_CONFIRM` | `UPLOAD_AND_APPLY_VERIFIED_EXPORT` |
| `CHATROOM_PROFILE_IMAGE_IMPORT_CREDENTIAL_PROVIDER` | `default-chain` |
| `CHATROOM_PROFILE_IMAGE_IMPORT_PROVIDER_EVIDENCE` | `REVIEWED_DATED_PASS_AND_NO_OBJECT_REMAINS` |

These confirmations are operator assertions, not substitutes for the retained
evidence. Use temporary least-privilege credentials, keep the V1 writers stopped,
and never store populated values or credentials in the repository.

## Bounded defaults

| Variable | Default | Accepted range |
| --- | ---: | ---: |
| `CHATROOM_GATEWAY_BIND_ADDRESS` | `127.0.0.1` | numeric IPv4/IPv6 literal |
| `CHATROOM_GATEWAY_PORT` | `9443` | `1..65535` |
| `CHATROOM_GATEWAY_ADMIN_ADDRESS` | `127.0.0.1` | numeric loopback only |
| `CHATROOM_GATEWAY_ADMIN_PORT` | `9090` | `1..65535` |
| `CHATROOM_GATEWAY_ADMIN_WORKERS` | `2` | `1..4` |
| `CHATROOM_GATEWAY_EVENT_LOOP_WORKERS` | `4` | `1..64` |
| `CHATROOM_GATEWAY_MAX_CONNECTIONS` | `10000` | `1..1000000` |
| `CHATROOM_GATEWAY_WRITE_BUFFER_LOW_BYTES` | `65536` | `1024..8388608` |
| `CHATROOM_GATEWAY_WRITE_BUFFER_HIGH_BYTES` | `262144` | `2048..16777216` |
| `CHATROOM_GATEWAY_AUTH_WORKERS` | `4` | `1..64` |
| `CHATROOM_GATEWAY_AUTH_QUEUE_CAPACITY` | `256` | `1..100000` |
| `CHATROOM_GATEWAY_MESSAGING_WORKERS` | `4` | `1..64` |
| `CHATROOM_GATEWAY_MESSAGING_QUEUE_CAPACITY` | `512` | `1..100000` |
| `CHATROOM_GATEWAY_HANDSHAKE_TIMEOUT_SECONDS` | `10` | `1..60` |
| `CHATROOM_GATEWAY_AUTH_TIMEOUT_SECONDS` | `30` | `1..300` |
| `CHATROOM_GATEWAY_IDLE_TIMEOUT_SECONDS` | `120` | `30..3600` |
| `CHATROOM_GATEWAY_HEARTBEAT_INTERVAL_SECONDS` | `30` | `5..300`; must be shorter than idle timeout |
| `CHATROOM_GATEWAY_DRAIN_TIMEOUT_SECONDS` | `15` | `0..300` |
| `CHATROOM_POSTGRES_POOL_MAXIMUM` | `8` | `1..64` |
| `CHATROOM_POSTGRES_POOL_MINIMUM_IDLE` | `1` | `0..64`, not above maximum |
| `CHATROOM_POSTGRES_CONNECTION_TIMEOUT_SECONDS` | `5` | `1..30` |
| `CHATROOM_GATEWAY_ADMISSION_WINDOW_SECONDS` | `60` | `1..3600` |
| `CHATROOM_GATEWAY_ATTEMPTS` | `600` | `1..1000000` |
| `CHATROOM_GATEWAY_PEER_ATTEMPTS` | `60` | `1..100000` |
| `CHATROOM_GATEWAY_ACCOUNT_ATTEMPTS` | `10` | `1..10000` |
| `CHATROOM_GATEWAY_MAX_LIMIT_KEYS` | `10000` | `16..1000000` |
| `CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED` | `false` | exact `true` or `false` |
| `CHATROOM_GATEWAY_FORWARD_WINDOW_SECONDS` | `60` | `1..3600` |
| `CHATROOM_GATEWAY_FORWARD_ATTEMPTS` | `120` | `1..10000` per account/window |
| `CHATROOM_GATEWAY_FORWARD_MAX_KEYS` | `10000` | `16..1000000` tracked accounts |

The high write-buffer watermark must be strictly greater than the low watermark.
Crossing it makes a Netty child channel non-writable so later messaging code can
apply bounded slow-consumer policy rather than accumulating unbounded output.

The heartbeat interval must be strictly shorter than the authenticated idle
timeout. The gateway sends an empty WebSocket Ping only after authentication and
only when no outbound traffic occurred during that interval. Browser and native
WebSocket stacks answer with Pong; a peer that produces no inbound traffic by
the idle timeout is closed. Do not use heartbeat payloads for application data.

Remote PostgreSQL URLs must contain exactly one `sslmode=verify-full` query
property. Credentials belong only in the dedicated secret values and are
rejected in the URL. For isolated local development, an unencrypted numeric
loopback URL is accepted only with
`CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL=true`; DNS names and remote addresses do
not qualify. The gateway-owned HikariCP pool uses bounded acquisition and
startup timeouts, validates a connection before listener bind, enables driver
TCP keepalive, and exports its diagnostics through JDK logging.

If `CHATROOM_GATEWAY_TRUSTED_PROXY_CIDRS` is absent or blank, all forwarding
headers are ignored and the direct socket peer is authoritative. When set to a
comma-separated CIDR list, `CHATROOM_GATEWAY_PROXY_MAX_HOPS` defaults to `4`
and accepts `1..16`. The edge proxy must overwrite/sanitize forwarding headers
and network policy must prevent untrusted direct access to that listener.

These defaults are not capacity claims. Before product routing, record a load
baseline and set deployment-specific values for CPU, database pool size,
reconnect storms, Argon2 work, queue saturation, and slow consumers.
Authentication and messaging use separate bounded worker pools so message
database work cannot consume password/session execution slots. Both still share
the bounded PostgreSQL pool, so their sizes must be tuned together.

Message forwarding is independently default-off. The three forwarding admission
values are validated even while the feature is disabled so an invalid rollout
cannot bind and later surprise an operator. Enabling it affects only new
handshakes after a gateway restart; existing connections keep their negotiated
capability set. Follow
[`MESSAGE_FORWARDING_ACTIVATION.md`](MESSAGE_FORWARDING_ACTIVATION.md) for the
gateway-first activation and client-first rollback contract.

The loopback-only `/metrics` response includes fixed-cardinality messaging
outcome counters and current message-worker active/queue gauges. It deliberately
contains no account, device, peer, session, conversation, or message labels.
It also reports
`chat_gateway_messaging_slow_consumer_maximum_bytes_before_writable`, the
process-lifetime maximum sampled immediately before an unwritable live
subscriber is closed. This is the amount Netty reports must drain to recover
writability, not total pending bytes, current backlog, or a capacity threshold.
It also exposes fixed attachment-cleanup counters plus consecutive-failure and
next-delay gauges. Those values remain zero because the M3 composition root does
not start the cleanup loop before real-provider capability acceptance.

The same loopback listener exposes exact GET-only `/identity` with release
version, source revision, runtime protocol version, and compatibility epoch. It
uses deterministic JSON plus `no-store`/`nosniff`, contains no secrets or user
identifiers, and must not be exposed through the public edge.
The loopback `/metrics` response repeats the same immutable values in
`chat_gateway_release_info`; this permits fleet-version dashboards without
making mutable image tags authoritative.

The process accepts no command-line configuration. On startup it validates the
existing Flyway migration state and database pool before serving, starts the
loopback admin endpoint as not ready, binds WSS, then returns HTTP 200 from
`/health/ready`. Shutdown first returns 503 readiness, stops new product
connections, and keeps established channels for up to the configured drain
timeout. It then force-closes remaining channels and releases the admin server,
messaging workers, authentication workers, and pool in reverse ownership order.
The same dynamic readiness value is also available as unauthenticated
`GET`/`HEAD /health/ready` on the TLS product listener so a remote load balancer
does not need access to the loopback admin interface. That request still passes
the configured Host and trusted-proxy policies, returns only `ready` or
`not_ready`, sets `Cache-Control: no-store`, rejects other methods, and fails
closed if dependency inspection throws. Restrict direct backend network access
to the load balancer; keep admin liveness and metrics loopback-only.
The load balancer must stop routing on readiness failure and its
deregistration/termination grace must exceed the application timeout. A value
of zero restores immediate shutdown; neither default is a fleet capacity claim.
Use the reviewed HAProxy generation contract in
[`HA_PROXY_GATEWAY.md`](HA_PROXY_GATEWAY.md); do not point an external proxy at
the loopback admin port.
Do not route users to this M3 runtime yet: conversation discovery, supported-
client adoption, and cutover/rollback rehearsal remain unfinished.
