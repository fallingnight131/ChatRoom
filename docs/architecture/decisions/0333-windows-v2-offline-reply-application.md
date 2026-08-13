# ADR-0333: Windows V2 Offline Reply Application

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The Windows V2 codec and isolated SQLite store now define strict transport and
durable boundaries, but neither should own product orchestration. Reply creation
must persist before network dispatch, reconnect must replay only durable pending
intent, and history continuation must start from the committed local cursor.

## Decision

- Add a detached Windows messaging application service that owns the codec and
  repository orchestration for one authenticated account/device context.
- Allow a reply only to an accepted target currently present in the local
  conversation snapshot. Create one client message ID, persist the complete
  reply intent first, then dispatch type 105 when connected.
- On disconnect, abandon only in-memory correlations and preserve durable
  pending rows. On reconnect, replay bounded pending work with the same client
  message ID, target ID, and text; cap wire work through the codec's 32-command
  pending bound.
- Reconcile ACKs into the same optimistic row. Permanent protocol errors mark
  that row failed for explicit user retry. Retryable errors remain pending but
  are deferred until a fresh reconnect, preventing an immediate retry loop.
- Start history reads at the durable cursor, reject non-advancing/stale pages in
  the codec, commit a page atomically, and merge live messages without moving
  the history cursor. Storage or protocol corruption tears down the messaging
  session so reconnect repair can run from durable state.
- Keep the service detached from the Qt WSS product transport and Widgets. Recall
  and deletion mutation projection, view models, rendering, focus behavior, and
  default-off product composition remain required before Windows reply delivery
  is complete.

## Consequences

The Windows reply workflow now has one testable source of orchestration truth
and preserves intent across offline/reconnect/ambiguous delivery. Product wiring
cannot bypass SQLite by sending directly through the protocol client.

Because recalled/deleted target state is not yet projected into the Windows
cache, this slice is not a user-visible completion and stays detached.

## Verification

The application test covers persist-before-type-105 dispatch, exact target and
client ID, ACK reconciliation, offline staging, reconnect replay, retryable
deferral without a hot loop, permanent failure, explicit same-ID retry, and
cursor-based atomic history reply merge. The protocol test also covers strict
request cursor correlation and disconnect abandonment.

## Rollback

Remove the detached service and test target. The codec and local store remain
usable building blocks; no product gate, V1 path, or server state changes.
