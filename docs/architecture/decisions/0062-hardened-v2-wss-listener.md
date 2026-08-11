# ADR-0062: Hardened V2 WSS Listener

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

V2 transport policies and application handlers were individually implemented,
but no owned server lifecycle composed them on a real TLS socket. Enabling a
generic Netty listener without fixed ordering, connection/backpressure bounds,
or deterministic shutdown would expose slow-handshake, memory-growth, policy-
bypass, and deployment-readiness risks.

## Decision

- Keep the independently runnable gateway boundary in `im-gateway`; add an
  owned `V2GatewayServer` component but do not start it from `GatewayMain` until
  PostgreSQL, cryptography, workers, admin readiness, and shutdown order are
  wired in the composition root.
- Validate the PEM certificate/private key into a TLS 1.3/1.2 server
  `SslContext` before bind. Install handlers in this order: process connection cap, TLS, upstream
  reader-idle timer, bounded HTTP codec/aggregation, exact Host, trusted-proxy
  peer resolution, endpoint/Origin/subprotocol policy, WebSocket negotiation,
  and post-upgrade V2 application composition.
- Use Netty's `/v2` prefix matcher only as a routing bridge. The preceding
  endpoint policy remains authoritative and accepts only exact `/v2/web` and
  `/v2/windows` paths.
- Bound HTTP line/header/chunk/content sizes, WebSocket frame size, TLS and HTTP
  upgrade time, active child channels, and outbound write-buffer watermarks.
  Disable extensions and mask mismatch, require masked client frames, validate
  UTF-8 control data, and drop handled pong frames before the application path.
- Close the listener first, then child channels, then worker and boss event-loop
  groups with a bounded grace period. A last-resort handler logs only a fixed
  event and exception-class category before closing a failed channel.

## Consequences

- A real WSS component now enforces the previously documented pre-upgrade and
  post-upgrade policies, including a hard process-local connection limit and
  slow-consumer write watermarks.
- This is not a production cutover. `GatewayMain` remains inactive and no
  PostgreSQL credentials, authentication workers, admin readiness, or product
  route are connected yet.
- Process-local connection caps do not constitute multi-gateway admission or a
  capacity claim; deployment values require M3 load evidence and M5 coordination
  where appropriate.

## Verification

Tests cover connection-cap denial/recovery, upgrade deadline expiry, application
pipeline installation only after handshake, invalid TLS fail-before-bind, real
loopback TLS handshake, successful `/v2/windows` `chat.v2` upgrade, missing-
subprotocol HTTP rejection, duplicate start denial, and idempotent shutdown.

## Rollback

Remove the unused listener/lifecycle classes and new bounded configuration
values. Because `GatewayMain` does not construct the component, rollback does
not alter an active traffic route or persistent data.
