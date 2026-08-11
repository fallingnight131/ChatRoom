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
| `CHATROOM_GATEWAY_HANDSHAKE_TIMEOUT_SECONDS` | `10` | `1..60` |
| `CHATROOM_GATEWAY_AUTH_TIMEOUT_SECONDS` | `30` | `1..300` |
| `CHATROOM_GATEWAY_IDLE_TIMEOUT_SECONDS` | `120` | `30..3600` |
| `CHATROOM_POSTGRES_POOL_MAXIMUM` | `8` | `1..64` |
| `CHATROOM_POSTGRES_POOL_MINIMUM_IDLE` | `1` | `0..64`, not above maximum |
| `CHATROOM_POSTGRES_CONNECTION_TIMEOUT_SECONDS` | `5` | `1..30` |
| `CHATROOM_GATEWAY_ADMISSION_WINDOW_SECONDS` | `60` | `1..3600` |
| `CHATROOM_GATEWAY_ATTEMPTS` | `600` | `1..1000000` |
| `CHATROOM_GATEWAY_PEER_ATTEMPTS` | `60` | `1..100000` |
| `CHATROOM_GATEWAY_ACCOUNT_ATTEMPTS` | `10` | `1..10000` |
| `CHATROOM_GATEWAY_MAX_LIMIT_KEYS` | `10000` | `16..1000000` |

The high write-buffer watermark must be strictly greater than the low watermark.
Crossing it makes a Netty child channel non-writable so later messaging code can
apply bounded slow-consumer policy rather than accumulating unbounded output.

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

The process accepts no command-line configuration. On startup it validates the
existing Flyway migration state and database pool before serving, starts the
loopback admin endpoint as not ready, binds WSS, then returns HTTP 200 from
`/health/ready`. Shutdown clears readiness and releases listener, admin, workers,
and pool in reverse ownership order. Do not route users to this M3 runtime yet:
durable V2 conversation/message commands and cutover/rollback rehearsal remain
unfinished.
