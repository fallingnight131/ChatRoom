# ADR-0081: History-first Web V2 Outbox Recovery

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

V2 snapshots retain optimistic commands across reload and ADR-0080 restores a
session after transient disconnect. Blindly replaying immediately can duplicate
work whose ACK was lost, skip reconciliation, or exceed the protocol's bounded
pending-request registry. Leaving recovered commands forever in `sending` also
misrepresents progress.

## Decision

- Bound each V2 conversation snapshot independently to 500 accepted messages and
  100 unresolved (`sending` or `failed`) commands. Reject a new optimistic send
  when the unresolved boundary is full.
- After authentication/resume and cache hydration, synchronize sequence history
  first. Merge by server message ID or `client_message_id`; this resolves a
  command whose durable ACK was lost before deciding what remains.
- Replay only commands still in `sending`, with the original client message ID,
  and at most one replay request per conversation at a time. Dispatch the next
  command only after the prior durable acceptance.
- Never automatically replay `failed` commands. If a replay returns a protocol
  error, mark that command failed, stop the queue, and mark undispatched queued
  commands `REPLAY_PAUSED` for explicit user retry. A transport send failure uses
  `TRANSPORT_UNAVAILABLE` and also stops the queue.
- Run automatic replay at most once per authenticated session generation and
  conversation. A later reconnect/resume creates a new generation only after
  history synchronization is requested again.

## Consequences

Recovery preserves at-least-once semantics without a request burst or false
`sending` state. Serial replay is deliberately slower than bulk resend but stays
inside client/server pending and worker bounds. Users need retry/cancel controls
before V2 UI rollout, especially when the 100-command boundary is reached.

## Verification

Tests cover independent 500/100 persistence bounds, refusal at capacity, no
replay before history, same-key ACK-loss suppression, one-at-a-time dispatch,
once-per-generation behavior, exclusion of failed commands, queue stop on error,
and visible `REPLAY_PAUSED` state. The complete Web test/build gate remains
required.

## Rollback

Remove automatic replay and retain unresolved commands for manual retry. The
idempotent protocol, cache, session recovery, and live V1 product path remain
unchanged.
