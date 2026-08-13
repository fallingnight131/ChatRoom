# ADR-0382: Web V2 Bounded Edge Failover

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0381 proves that a durable session can explicitly move between two edge
failure domains, but the Web V2 transport still retries one fixed URL forever.
Browser network-online signals cannot identify a healthy edge, and accepting a
runtime redirect or user-provided authority would weaken the existing endpoint
trust boundary.

## Decision

- Keep one required primary Web V2 WSS URL and add at most three ordered
  fallback URLs as immutable, public build metadata.
- Require every entry to be a unique exact `wss://authority/v2/web` URL without
  credentials, query, or fragment. Any malformed list disables the preview
  runtime before browser storage or network access.
- Attempt the current entry with the existing phase timeouts and full-jitter
  exponential reconnect policy. A synchronous socket-construction failure or
  socket close advances one position in the bounded circular list.
- Browser-offline transitions do not advance the list or consume reconnect
  budget. A successful connection keeps its selected entry until that socket
  later fails.
- Keep session resume proofs memory-only and reuse the existing protocol resume
  flow after moving to another configured edge.
- Do not accept endpoint discovery from LocalStorage, query parameters, gateway
  frames, redirects, or an unauthenticated network response.

## Consequences

An enabled Web V2 preview candidate can recover automatically when one
configured edge becomes unreachable without relaxing WSS route validation or
persisting session credentials. Operators must keep every configured authority
in the Web CSP `connect-src`, gateway Origin/Host policy, certificates, and
monitoring.

This is deterministic client selection, not service discovery. It does not
prove DNS/GSLB behavior, automatic removal of a degraded-but-connectable edge,
cross-region data availability, production browser deployment, or the matching
Windows behavior. The supported V1 Web path remains unchanged.

## Verification

Run `npm test` and `npm run build` in `WebClient/`. Transport tests must prove
ordered rotation, wraparound, exact endpoint validation, offline pause, bounded
backoff, and memory-only session resume. The preview CI build must include a
valid fallback list.

## Rollback

Redeploy the previous immutable Web asset version or omit
`VITE_CHAT_V2_WSS_FALLBACK_URLS`. The primary URL and single-endpoint behavior
remain compatible; no protocol or persistent browser schema changes are made.
