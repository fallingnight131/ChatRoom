# ADR-0059: WebSocket Host Authority Policy

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

An allowed browser Origin identifies the initiating page, not the authority the
request reached. Reverse-proxy mistakes, DNS rebinding, or an unexpected Host can
route an otherwise valid upgrade into the gateway. Windows requests have no
Origin at all, so they especially require an independent target-authority gate.

## Decision

- Require exactly one Host value before proxy/origin/WebSocket processing can
  complete. Reject missing, duplicate, malformed, or unapproved values with a
  fixed empty 400 response and close.
- Configure 1..32 exact TLS authorities. Accept host or bracketed IPv6 forms
  with an optional valid port, normalize case and default port 443, and reject
  scheme, user info, path, query, fragment, whitespace, empty/zero/invalid ports,
  and values longer than 255 characters.
- Never resolve Host values through DNS and never use suffix/wildcard matching.
  Every additional hostname is explicit security configuration.
- Process at most one HTTP request on the dedicated upgrade connection. A repeat
  request fails closed.
- Keep Host, Origin, and trusted-proxy policy as separate ordered handlers so
  each trust concern remains testable. No listener installs them yet.

## Consequences

- Web and Windows upgrades cannot reach the protocol pipeline through an
  unexpected virtual host even when other headers look valid.
- Deployments behind a load balancer must preserve an approved external Host or
  deliberately configure the reviewed internal authority.
- This is not TLS certificate hostname validation for clients; WSS and correct
  certificate deployment remain required.

## Verification

Tests cover case/default-port normalization, explicit non-default ports,
bracketed IPv6, missing/duplicate/hostile/path-bearing values, insecure
scheme/user-info configuration, normalized duplicates, successful forwarding,
repeat request rejection, empty generic errors, and reference ownership.

## Rollback

Remove the unused policy/handler and tests. No listener installs them, so active
V1 and inactive V2 network behavior are unchanged.
