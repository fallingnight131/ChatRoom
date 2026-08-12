# ADR-0143: V1 Same-Origin HTTP Health Contract

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestones: M1, M4

## Context

The supported Web client requires same-origin `/api/` routing, but the V1 HTTP
server previously exposed only tokenized upload and download resources. A
release probe could receive a CDN or proxy 404 and had no unambiguous way to
distinguish a correctly routed application data plane from a broken route.
Using an authenticated file request would put credentials into release tooling
and couple deployment health to user data.

## Decision

- Add the exact query-free `GET /api/health` V1 compatibility endpoint.
- Return only the bounded canonical JSON body
  `{"protocol":"v1","status":"ok"}` plus a trailing newline. Do not expose
  build, host, database, account, file, dependency, or secret information.
- Require status 200, `application/json; charset=utf-8`, `Cache-Control:
  no-store`, `X-Content-Type-Options: nosniff`, exact content length, and a
  closed connection.
- Do not emit wildcard CORS on this same-origin endpoint. Keep existing file
  compatibility responses unchanged.
- Reject query strings and path variants. Non-GET methods retain the existing
  method rejection. The endpoint performs no database operation and grants no
  readiness or durability guarantee beyond V1 HTTP process/routing reachability.

## Consequences

An external HTTPS release probe can now prove that `/api/` reaches the intended
V1 application without carrying a file token. Old clients are unaffected
because no existing message or response changes. Old servers do not implement
the endpoint, so a deployment using the new release gate must upgrade its
server or deliberately remain outside the new M4 acceptance boundary.

## Migration and Rollback

No persistent data or client migration is required. Rolling back the server
removes the health endpoint and therefore causes the newer release gate to fail
closed; it does not affect V1 chat or file messages.

## Verification

- a real headless V1 server returns the exact status, body, and unique headers;
- wildcard CORS is absent;
- a trailing slash, any query, and POST fail without a healthy result;
- the test is part of `python3 tools/verify_m0.py --v1-smoke`.
