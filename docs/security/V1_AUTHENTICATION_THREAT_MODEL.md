# V1 Authentication Threat Model

## Scope

This living document covers V1 Qt/Web login credentials, browser credential
lifetime, transport assumptions, and server password verification. The first M1
slice only changes browser persistence; unresolved risks below remain open.

## Assets and Trust Boundaries

- Account password and authenticated user identity.
- Browser page, Web Storage, and same-origin JavaScript execution.
- Qt/Web transport between client and `ChatServer`.
- SQLite `users.password_hash` and `users.salt` fields.
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
| Credential leakage through logs | No intended password logging | Add explicit log redaction tests and structured authentication errors |
| Stale browser state impersonates a session | Router now checks live Pinia authenticated state | Server must continue rejecting unauthenticated socket commands |

## Security Invariants

- Web passwords must never be written to `localStorage`, `sessionStorage`,
  IndexedDB, URL query strings, or logs.
- Logout, forced offline, and failed reauthentication clear memory credentials.
- A refresh requires a new login until a revocable server session exists.
- Server address/theme preferences are non-secret and may remain persisted.
- Public credentials must not be sent over plaintext WebSocket/TCP.
- Wrong-password attempts must never mutate a stored legacy hash.
- Authentication logs may contain a numeric user ID and error category, never a
  password, salt, encoded hash, or login payload.

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

The automated tests cover credential lifetime and storage regression. Browser
integration on 2026-07-11 verified register/login, refresh-to-login, visible
disconnect state, and successful current-page reauthentication after the V1
server restarted. Forced-offline, username/password change, log redaction, and
transport/TLS enforcement remain required before public deployment.
