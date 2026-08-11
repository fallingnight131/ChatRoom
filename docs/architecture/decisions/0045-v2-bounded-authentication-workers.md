# ADR-0045: V2 Bounded Authentication Workers

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

Fresh login can perform Argon2id work and PostgreSQL I/O. Merely injecting an
arbitrary `Executor` keeps this work off the Netty event loop but does not bound
threads or queued requests. An unbounded executor would turn authentication
traffic into memory, CPU, and database-pool exhaustion.

This slice does not yet own a public listener or enough trusted peer context to
define the account/direct-IP/process window limiter. It must bound admitted work
without overstating broader abuse protection.

## Decision

- Add a gateway-owned fixed-size `AuthenticationWorkerPool` with an
  `ArrayBlockingQueue` and abort-on-saturation policy. It implements `Executor`
  for the transport adapter and `AutoCloseable` for explicit lifecycle
  ownership.
- Validate worker count, queue capacity, and shutdown timeout before allocating
  threads. No caller can configure more than 64 workers or 100,000 queued
  commands through this boundary.
- Name non-daemon workers `chat-auth-N`. Graceful close stops admission and
  waits for the configured duration, then interrupts remaining work and
  preserves caller interruption.
- When the pool rejects admission, clear the owned password command without
  invoking the authentication use case. Return generic
  `AuthenticationRejected(RATE_LIMITED)` with a fixed one-second retry hint,
  close the connection, and record only a non-secret saturation outcome.
- Do not label queue admission as account/IP abuse protection. A separate slice
  must add bounded, observable limits before this executor and replace
  process-local policy with Redis coordination when gateways scale horizontally.

## Consequences

- The maximum concurrent and waiting authentication work is explicit and
  independently testable. Netty event loops never perform password work.
- Queue saturation sheds load before Argon2id or database access. The fixed
  retry hint is protocol guidance, not a capacity guarantee.
- Worker and queue sizes are constructor configuration rather than product
  defaults. Listener bootstrap and measured deployment tuning remain pending.

## Verification

Tests hold one real worker, fill the one-element queue, verify the third command
is rejected, then prove the queued task runs and shutdown rejects new work.
Embedded-channel tests verify saturation never invokes the use case, returns the
generic rate-limited payload, records one non-secret event, and closes.

## Rollback

Remove the unused pool and inject another executor into the inactive V2 handler.
Do not enable a listener with an unbounded replacement. V1 remains authoritative.
