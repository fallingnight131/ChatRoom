# ADR-0057: Trusted Proxy Peer Resolution Policy

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

Authentication admission currently uses the direct socket peer. Behind a load
balancer that aggregates every user into the proxy's address; blindly trusting
`X-Forwarded-For` instead lets any direct client choose a limiter identity and
bypass peer controls. The gateway needs one explicit, bounded trust algorithm
before an HTTP/WebSocket listener can consume forwarding headers.

## Decision

- Default to direct-peer-only resolution. Ignore forwarding claims from every
  direct peer unless its resolved address belongs to an explicitly configured
  trusted IPv4/IPv6 CIDR.
- Accept only numeric IPv4/IPv6 literals and CIDR notation. Never resolve proxy
  configuration or forwarding values through DNS. Bound configuration to 32
  CIDRs, forwarding input to 512 ASCII bytes/four header fields, and the
  configured hop limit to 1..16.
- For a trusted direct proxy, require a forwarding chain. Walk it from right to
  left while the current hop is trusted and select the first untrusted address.
  This ignores client-injected values to the left of that address when the edge
  proxy appends a sanitized client address.
- Reject a trusted proxy with missing, malformed, hostname-based, or overlong
  forwarding input. Do not silently fall back to the proxy address in those
  cases because that would hide a deployment error and collapse rate limiting.
- Return only a canonical address plus a fixed enum decision. Do not include raw
  header text in errors, metrics, or logs.
- Keep this policy transport-independent and inactive until the future HTTP
  upgrade handler is added. That handler must run before WebSocket upgrade,
  close rejected resolutions, and pass only the accepted canonical address to
  authentication admission. The reverse proxy must overwrite/sanitize inbound
  forwarding headers and be protected by network policy.

## Consequences

- Direct deployments remain safe without configuration, and a future proxied
  deployment has an auditable chain algorithm rather than ad hoc header parsing.
- CIDR trust is security configuration. A broad or stale CIDR can authorize a
  hostile sender to choose forwarded addresses; deployment review remains
  mandatory.
- This does not provide distributed rate limiting. M5 still needs coordinated
  state across gateways.

## Verification

Tests prove direct spoofing is ignored, a trusted multi-proxy chain resolves
right-to-left, injected leftmost values do not win, IPv6 CIDRs work, trusted
missing/hostname/over-hop inputs reject, unresolved direct peers reject, and
invalid prefix/configuration bounds fail.

## Rollback

Remove the unused policy types and tests. No listener consumes forwarding
headers yet, so rollback changes no network behavior.
