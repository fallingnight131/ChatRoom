# ADR-0264: Compose Detached V1 Room Messaging

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0263

## Decision

Compose strict bounded `CHAT_MSG` submission and `CHAT_SEND_RSP` handling in the
detached Java V1 module. Bind sender account, device, username, and display name
to authenticated channel state. Accept only a positive mapped room ID, stable
client message ID, bounded text/emoji content, and presentation type. Execute at
most one submission per channel off the event loop.

After first durable acceptance, return the authoritative ACK, echo one live
message to the sender, and route the same notification to eligible local
recipients. Eligibility is not trusted from gateway memory: snapshot at most
10,000 connected account IDs and filter them in one PostgreSQL query through
enabled V1 account mappings and current active GROUP membership. Re-check each
connection when scheduling its write. Exact retry returns `duplicate=true` and
emits no live message. Malformed, saturated, dependency-failed, and stale
completion paths fail closed. Fixed telemetry and boundary exception logging do
not contain message bodies, usernames, account IDs, or room IDs.

This does not claim recipient delivery or multi-gateway routing. Users who join
or reconnect during a submission recover from durable history; cross-instance
routing remains M5. The 10,000-candidate cap bounds detached single-gateway
work and is not a capacity claim. The product listener remains unchanged.

Rollback removes the handler from detached module composition; the additive
audience port and durable messages remain valid.

## Verification

Application tests prove empty, bounded, subset-only audience filtering. Handler
tests cover first-only sender/recipient notification, duplicate suppression,
authorization rejection, saturation, and detached pass-through. Disposable
PostgreSQL proves two imported room members can log in, receive an authoritative
first acceptance and local notification, and retry without a second broadcast;
the migration and adapter suites prove the batch membership filter.
