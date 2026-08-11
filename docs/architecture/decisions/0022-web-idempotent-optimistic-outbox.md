# ADR-0022: Web Idempotent Optimistic Text Outbox

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M2

## Context

The Web composer previously waited for the server's live echo before rendering a
sent text or emoji. Transport loss before acknowledgement also left no durable
client intent to retry. M1 already provides durable server acceptance keyed by
`clientMessageId`, so M2 can improve perceived latency without inventing a new
wire guarantee.

## Decision

- Render room/direct text and emoji immediately with client-only
  `deliveryState: sending` and a stable `clientMessageId`.
- Persist unresolved optimistic messages inside the account/conversation
  IndexedDB snapshot introduced by ADR-0021.
- On reconnect or cache hydration, resend unresolved messages with the exact
  same `clientMessageId`. Never generate a new key for a retry.
- Reconcile `CHAT_SEND_RSP`/`FRIEND_CHAT_SEND_RSP`, live echo, and history by
  stable client/server identity. Durable acceptance changes the local state to
  `accepted`; rejection changes it to `failed` and exposes an explicit retry.
- Do not allow recall, forwarding, or administration actions on messages without
  a durable server ID.
- Limit this slice to text and emoji. Attachment upload already has durable
  finalization identity, but optimistic attachment cards and restartable binary
  sources require a separate design.

## Consequences

Sending feels immediate, reload/reconnect can safely recover an unresolved
intent, and duplicate acknowledgements or echoes converge on one rendered
message. `accepted` still means only that the server durably committed the
message; this does not claim destination delivery or read acknowledgement.

An indefinitely disconnected message remains `sending` until transport recovery.
A structured server rejection remains `failed` until the user retries or a later
outbox-management slice provides discard/edit controls.

## Rollback

Route the composer back to the WebSocket service and stop rendering client-only
messages. Persisted optimistic records are harmless metadata; authoritative
history continues to reconcile any message already accepted by the server.

## Verification

- pure unit tests cover optimistic creation, durable acceptance, rejection, and
  selection of unresolved own messages;
- existing reconciliation tests cover stable-ID authoritative merging;
- Web tests and production build are required;
- reconnect/reload retry uses the existing V1 idempotency integration coverage;
  browser automation for visual status and manual retry remains a later release
  gate.
