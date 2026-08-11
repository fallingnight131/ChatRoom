# ADR-0084: Browser-aware Web V2 Reconnect

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The V2 transport uses bounded jittered reconnect after unexpected socket close.
When the browser already reports that the network is offline, continuing timers
cannot succeed, wastes client/server resources, and presents an ambiguous
"waiting" state. Treating the browser signal as proof that the server is
reachable would be unsafe because `online` only means a network interface may be
available.

## Decision

- Add an explicit `offline` transport state and injectable network observation
  boundary around browser `online`/`offline` events and `navigator.onLine`.
- On offline transition, cancel phase/reconnect timers, close the current socket
  with a normal going-away outcome, clear connection-local protocol state, and
  preserve only the memory-held rotated resume proof.
- Do not create sockets or schedule retry timers while offline. On an online
  transition, reset reconnect backoff and attempt one connection immediately;
  ordinary handshake, authentication timeout, and retry rules still determine
  whether the gateway is actually reachable.
- Subscribe only while the transport is desired and always detach on stop.
  Browser API denial degrades to ordinary socket-based reachability rather than
  preventing connection.
- Surface offline state in the engineering preview. Do not classify browser
  network state as authentication, delivery, or server-health evidence.

## Consequences

Offline periods no longer create futile reconnect churn, and network restoration
gets a prompt resume attempt. Captive portals, DNS failures, and gateway outages
still follow normal bounded backoff because an `online` signal is only a hint.
Session proof confidentiality and rotation remain unchanged.

## Verification

Deterministic transport tests prove offline start creates no socket/timer,
offline transition closes and cancels work, online recovery reconnects
immediately, the memory-only proof is used for resume after recovery, and stop
removes observation. Full Web tests and both stable/preview builds remain gates.

## Rollback

Remove browser observation and the `offline` state; the existing jittered
reconnect behavior remains compatible. No protocol, server, or persistent data
format changes are involved.
