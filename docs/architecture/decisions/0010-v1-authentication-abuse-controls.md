# ADR-0010: Bound V1 Authentication Work Across Connections

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

V1 already limits one connection to five login, registration, or password-change
commands per minute. An attacker can bypass that boundary by opening many TCP or
WebSocket connections. Password verification and registration use Argon2id, so
unbounded cross-connection authentication work can exhaust CPU even when frames
and fields are individually valid.

The current server is a single Qt process. It has no trusted proxy identity
configuration, shared Redis limiter, metrics backend, or Java gateway. The
smallest compatible improvement must therefore protect the current process
without trusting client-supplied forwarding headers or introducing distributed
infrastructure early.

Affected quality attributes are authentication security, availability,
compatibility, operability, and bounded memory use.

## Decision

- Add a focused in-memory `AuthenticationAbuseGuard` owned and invoked by the
  `ChatServer` application thread before valid authentication requests reach
  SQLite/password hashing.
- Apply fixed-window limits at three cumulative dimensions:
  - all authentication work handled by the server process (gateway dimension);
  - the direct TCP/WebSocket peer address (IP dimension);
  - the trimmed, case-folded account identifier (account dimension).
- Count login, registration, and authenticated password-change attempts. Retain
  the existing per-connection five-per-minute disconnect as an earlier guard.
- Default to a 60-second window with 600 gateway, 60 direct-peer IP, and 10
  account attempts. Allow operators to tune those values through bounded process
  environment variables and log the effective non-secret configuration at
  startup.
- Remove an account bucket after successful login, registration, or password
  change. Gateway and IP attempts remain counted so valid credentials cannot be
  used to bypass process-level work limits.
- Bound IP and account maps to 4,096 active keys by default, expire completed
  windows, and fail closed for new keys while capacity is exhausted.
- Never trust V1 JSON data or unverified proxy-forwarding headers as the client
  IP. Behind a reverse proxy, the direct peer may be the proxy itself; operators
  must size the IP limit for that aggregate until a trusted-proxy boundary is
  designed.
- Preserve the V1 wire contract. A denied operation uses its existing
  `LOGIN_RSP`, `REGISTER_RSP`, or `CHANGE_PASSWORD_RSP` type with
  `success: false` and the existing optional `error` field. No new message type
  or required field is added.
- Emit structured `AuthAbuse` configuration and sampled denial logs containing
  the operation, limiting dimension, retry duration, aggregate counters, and
  active key counts. Log the first denial and power-of-two cumulative denials
  per dimension to bound log amplification. Do not log the account identifier,
  peer address, password, hash, salt, or request payload.

Configuration variables:

| Variable | Default | Accepted range |
| --- | ---: | ---: |
| `CHATROOM_AUTH_WINDOW_MS` | 60000 | 1000-3600000 |
| `CHATROOM_AUTH_GATEWAY_ATTEMPTS` | 600 | 1-1000000 |
| `CHATROOM_AUTH_IP_ATTEMPTS` | 60 | 1-100000 |
| `CHATROOM_AUTH_ACCOUNT_ATTEMPTS` | 10 | 1-10000 |
| `CHATROOM_AUTH_MAX_TRACKED_KEYS` | 4096 | 16-1000000 |

Invalid or out-of-range values fall back to the documented default and the
effective configuration remains visible in the startup log.

## Alternatives Considered

- Keep only the connection-scoped limit: rejected because reconnecting or
  opening parallel sockets bypasses it.
- Trust `X-Forwarded-For` immediately: rejected because direct TCP has no such
  header and an unverified header lets an attacker choose arbitrary limiter
  keys.
- Persist failures in SQLite: rejected because abuse traffic would amplify
  durable writes and mix ephemeral protection state with primary user data.
- Introduce Redis now: rejected because V1 is single-node and M5 owns
  multi-gateway shared rate limiting after an operations plan exists.
- Sleep in the Qt application thread for progressive delay: rejected because it
  blocks unrelated WebSocket and business work.

## Consequences

Parallel connections can no longer create unbounded authentication work within
one V1 server process. Existing clients continue to receive familiar failure
responses and display the server-provided error text.

Fixed-window account limiting can temporarily deny the legitimate owner after an
attack, and direct-peer IP limiting can aggregate many users behind one proxy or
NAT. Defaults are therefore tunable and must be adjusted from observed denial
logs without weakening the gateway ceiling. This slice provides structured log
signals, not a hosted metrics/alerting service.

Limiter state is intentionally process-local and resets on restart. It does not
provide cross-instance enforcement; the Java gateway/Redis design must replace
or coordinate it before horizontal scale.

Rollback consists of removing the guard invocation and environment settings,
but doing so restores cross-connection Argon2 work amplification and is not
suitable for an Internet-facing V1 deployment.

## Verification

`Tests/v1_authentication_abuse_test.py` launches real isolated server processes
and verifies:

- one account is limited across independent TCP connections;
- one direct peer is limited across different accounts and mixed TCP/WebSocket
  connections;
- the process/gateway ceiling spans different accounts and connections;
- the account window expires and a correct login succeeds afterward;
- login, registration, and password-change denial retain their existing V1
  response types;
- each denial emits a structured dimension/counter log; and
- the test password is absent from captured server output.

The established positive smoke, authorization, transport-limit, and field-input
suites run in the same command to protect compatible behavior and earlier
security boundaries.
