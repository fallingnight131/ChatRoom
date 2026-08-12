# ADR-0261: Compose Detached V1 Direct Recall

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0260

## Decision

Compose `FRIEND_RECALL_REQ` only in the detached V1 compatibility module. Parse
one integral legacy `messageId`; tolerate the optional peer username for wire
compatibility but never use it as authority. Bind the actor from authenticated
channel state and execute persistence on the bounded worker with one request in
flight per connection and stale-result suppression.

Return authoritative mapped peer, friendship, message, mutation sequence, and
duplicate state. Only first apply schedules `FRIEND_RECALL_NOTIFY` to the
authoritative process-local peer connection. Exact retry responds without a
notification. Malformed, saturated, dependency-failed, or encoding-failed paths
close safely; fixed telemetry contains no identity. The product listener remains
unchanged and multi-gateway routing remains M5.

## Verification

Embedded-channel tests prove authenticated actor binding, spoofed peer
irrelevance, compatible response/notification fields, duplicate notification
suppression, and malformed closure. PostgreSQL tests independently prove the
atomic durable decision. Disposable PostgreSQL composition additionally proves
send, replacement login, cursor recovery, first recall, peer notification,
duplicate suppression, and recall compensation through the next history page.
