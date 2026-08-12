# ADR-0254: Compose Detached V1 Direct Messaging

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Compose strict bounded `FRIEND_CHAT_MSG` submission and
`FRIEND_CHAT_SEND_RSP` handling in the detached Java V1 module. Bind sender
account/device/name to authenticated channel state and accept only the reviewed
text/emoji fields. Preserve explicit `clientMessageId` and the existing envelope
ID fallback. Execute one submission at a time off the event loop.

After first durable acceptance, write the ACK to the sender before one live
message echo and schedule the same authoritative notification to the target's
current process-local connection. Exact retry returns the original ACK with
`duplicate=true` and emits no live message. Rejections keep stable V1 error
codes; malformed, saturated, dependency-failed, or stale completion paths close
safely. Fixed telemetry contains no message content, usernames, or IDs.

This does not claim recipient delivery, multi-gateway routing, or product
activation. Offline recovery remains history/cursor based and multi-gateway
routing remains M5. The product listener remains unchanged.

## Verification

Codec tests cover explicit/fallback idempotency keys and mapped response/live
fields. The complete Java gate covers handler composition. Disposable PostgreSQL
proves two imported logins receive ACK plus first-only sender/recipient live
messages, duplicate ACK suppression, numeric mapping with no UUID exposure, and
history retention after friend removal.
