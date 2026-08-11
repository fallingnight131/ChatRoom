# V1 Authentication Threat Model

## Scope

This living document covers V1 Qt/Web login and room-password credentials,
browser credential lifetime, transport assumptions, and server secret
verification. Resource
authorization is maintained separately in
[`V1_AUTHORIZATION_MATRIX.md`](V1_AUTHORIZATION_MATRIX.md).

## Assets and Trust Boundaries

- Account password, room password, and authenticated user identity.
- Browser page, Web Storage, and same-origin JavaScript execution.
- Qt/Web transport between client and `ChatServer`.
- SQLite `users.password_hash`, `users.salt`, and `rooms.password` fields.
- Server logs, crash reports, and operator access.

The server is authoritative for authentication. A browser identity object or
route state is never proof of authentication.

## Threats and Current Controls

| Threat | Current control | Remaining risk / next action |
| --- | --- | --- |
| Password recovered from browser storage | Updated Web client keeps it in module memory and purges legacy session keys | XSS can still read live memory; add CSP and server sessions |
| Page refresh silently reuses password | Refresh loses memory credentials and routes to login | Replace password replay with revocable refresh/device sessions in V2 |
| Network interruption loses authenticated socket | Current-page memory credential reauthenticates the new V1 socket | Add bounded retry/UI failure tests and eventually token reauth |
| Credential interception | HTTPS pages select WSS | Plain HTTP still selects `ws://`; production TLS must become mandatory |
| Offline cracking of server database | New/changed passwords use libsodium Argon2id; successful legacy login upgrades salted SHA-256 rows | Back up before rollout, monitor upgrades, and complete migration before removing legacy verification |
| Room secret disclosure | Room passwords use Argon2id, legacy plaintext upgrades after a successful join, and status APIs never return the value | Existing rows remain plaintext until a correct join or administrator reset; old binaries cannot read upgraded rows |
| Credential leakage through logs | Authentication-abuse regression captures server output and rejects password leakage; structured denial logs omit account/IP/request data | Extend redaction coverage to every authentication and crash-report sink |
| Repeated expensive password work | Account and room-password fields are capped; connection, account/room, direct-peer IP, and process/gateway windows bound work before hashing | V1 state is process-local and direct-peer IP may aggregate a proxy; use trusted proxy identity and shared Redis enforcement only with the future gateway design |
| Stale browser state impersonates a session | Router now checks live Pinia authenticated state | Server must continue rejecting unauthenticated socket commands |

## Security Invariants

- Web passwords must never be written to `localStorage`, `sessionStorage`,
  IndexedDB, URL query strings, or logs.
- Logout, forced offline, and failed reauthentication clear memory credentials.
- A refresh requires a new login until a revocable server session exists.
- Server address/theme preferences are non-secret and may remain persisted.
- Public credentials must not be sent over plaintext WebSocket/TCP.
- Wrong-password attempts must never mutate a stored legacy hash.
- Room-password status APIs never return a plaintext value or encoded hash.
- Authentication logs may contain a numeric user ID and error category, never a
  password, salt, encoded hash, or login payload.
- Password input must be bounded before Argon2id, and one connection cannot
  trigger unbounded login/register/password-change work.
- Valid authentication work is also bounded across connections by process,
  direct-peer IP, and normalized account. Client-provided forwarding headers are
  not trusted as limiter identity.
- Sampled authentication-abuse denial logs expose
  operation/dimension/aggregate counts, not account identifiers, peer addresses,
  passwords, hashes, salts, or request payloads. Sampling prevents a denied
  request flood from becoming a linear log flood.

## Verification

Run:

```bash
cd WebClient
npm test
npm run build
```

Server password migration verification:

```bash
python3 tools/verify_m0.py --password-hash
```

Cross-connection authentication-abuse verification runs as part of:

```bash
python3 tools/verify_m0.py --v1-smoke
```

The automated tests cover credential lifetime/storage regression and real
TCP/WebSocket account/IP/gateway limits, expiry recovery, and password-log
redaction. Browser integration on 2026-07-11 verified register/login,
refresh-to-login, visible
disconnect state, and successful current-page reauthentication after the V1
server restarted. Forced-offline, full username/password-change log redaction,
trusted-proxy identity, and transport/TLS enforcement remain required before
public deployment.
