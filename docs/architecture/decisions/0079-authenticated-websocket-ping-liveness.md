# ADR-0079: Authenticated WebSocket Ping Liveness

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The gateway closes an authenticated connection after 120 seconds without inbound
traffic. Native Windows code can originate WebSocket Ping frames, but the browser
WebSocket API cannot. Without a server heartbeat, an otherwise healthy idle Web
session would disconnect repeatedly and enter reconnect/authentication flow.

## Decision

- Configure a positive authenticated writer-idle interval, defaulting to 30
  seconds and constrained to 5..300 seconds and strictly below the reader-idle
  timeout.
- On writer idle, send one empty WebSocket Ping only when server-side identity is
  bound. Do not send application data or identifying values in heartbeat frames.
- Keep the reader-idle timer before WebSocket control handling so browser/native
  automatic Pong bytes refresh connection activity even when Pong frames are
  dropped before application dispatch.
- Preserve the existing fixed 1001 `V2 idle timeout` close for a peer that remains
  silent. Handshake/authentication deadlines continue to govern unauthenticated
  connections; heartbeat does not extend them.

## Consequences

Healthy idle browser and Windows connections have a standards-based liveness
exchange without adding an application protocol message. Silent peers still
release gateway resources. One small control frame per idle interval adds
bounded bandwidth and event-loop work; production interval/capacity tuning needs
measured deployment evidence.

## Verification

Configuration tests cover the default and reject an interval equal to the idle
timeout. Embedded-channel tests prove writer idle is ignored before identity,
emits a final empty Ping after identity, and forwards reader idle to the existing
deterministic close handler. The ordered pipeline snapshot and complete Java
workspace tests must pass.

## Rollback

Remove writer-idle configuration and the heartbeat handler. The existing reader
idle close remains, but browser idle sessions again require application traffic
and therefore this rollback is not suitable for a V2 Web cutover.
