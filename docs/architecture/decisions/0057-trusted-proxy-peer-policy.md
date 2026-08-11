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
- Keep the policy transport-independent and add a reusable HTTP handler that
  runs before WebSocket upgrade. It closes rejected resolutions with a generic
  response, freezes one accepted canonical address in server-side channel
  state, rejects a second upgrade attempt, and makes authentication admission
  prefer that state over the raw socket peer. The handler is not installed on a
  listener yet. The reverse proxy must overwrite/sanitize inbound forwarding
  headers and be protected by network policy.

## Consequences

- Direct deployments remain safe without configuration, and a future proxied
  deployment has an auditable chain algorithm and one pre-upgrade enforcement
  point rather than ad hoc header parsing.
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
Handler tests prove accepted direct/proxied requests freeze the canonical
address, trusted missing forwarding and repeat upgrades close with a generic
400, retained HTTP messages remain balanced, and admission consumes the frozen
address.

## Rollback

Remove the unused policy/handler types, channel attribute, and tests. No listener
installs the handler yet, so rollback changes no network behavior.
