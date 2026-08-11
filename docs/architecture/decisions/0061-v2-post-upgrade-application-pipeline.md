# ADR-0061: V2 Post-Upgrade Application Pipeline

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The inactive V2 gateway had individually tested frame, phase-timeout,
negotiation, and authentication handlers, but no single composition boundary
defined their order after a successful WebSocket upgrade. Installing them in a
different order could parse application data before limits apply, authenticate
before version/platform binding, or leave authenticated idle connections open
indefinitely.

## Decision

- Provide one `V2ApplicationPipeline` installer for the post-upgrade path.
- Install bounded frame aggregation/decoding/encoding first, followed by phase
  deadlines, `ClientHello` negotiation, authentication/session resume, and the
  authenticated writer-idle heartbeat and reader-idle close handlers in that
  exact order.
- Keep the reader-idle timer in the future listener's pre-upgrade pipeline so
  WebSocket control frames can refresh it. The post-upgrade handler acts only
  after server-side identity binding; pre-authentication time is bounded by the
  independent handshake and authentication deadlines.
- Close an authenticated reader-idle connection with WebSocket status 1001 and
  the fixed reason `V2 idle timeout`. Do not expose identity or timing details.
- Require the fixed `chat.v2` WebSocket subprotocol at the HTTP policy boundary;
  the WebSocket negotiator must be configured with the same value.

## Consequences

- The listener composition root has one deterministic application-pipeline
  contract and cannot accidentally omit an implemented phase control.
- Ping/pong handling remains a WebSocket transport responsibility. Application
  traffic and valid control traffic both prevent reader-idle closure once the
  listener installs its upstream idle timer.
- This does not enable a socket or prove TLS/lifecycle integration. Those remain
  required before V2 can receive traffic.

## Verification

Embedded-channel tests assert exact handler order, fixed subprotocol rejection,
authenticated idle closure/status/reason, and pass-through behavior before
authentication and for non-reader idle events.

## Rollback

Remove the unused installer and authenticated-idle handler, revert the fixed
subprotocol check, and restore the previous protocol documentation. No active
listener currently installs this pipeline.
