# ADR-0270: Compose Detached V1 Room Recall

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0269

## Decision

Compose strict bounded V1 `RECALL_REQ`, `RECALL_RSP`, and `RECALL_NOTIFY`
handling in the detached Java compatibility module. Bind the actor username and
account to authenticated channel state; accept only positive mapped room and
message IDs as application intent. Execute at most one mutation per channel off
the event loop.

After first durable apply, write the authoritative response, echo one recall
notification to the sender, and route the same notification to eligible local
recipients. Snapshot connected account IDs before dispatch, batch-filter them
through enabled V1 mappings and current GROUP membership after commit, exclude
the sender from registry routing, and re-check each connection when scheduling
its write. Exact retry returns `duplicate=true` and emits no notification.

Malformed, concurrent, saturated, dependency-failed, audience-failed, encoding-
failed, or stale completion paths fail closed. Fixed telemetry records only
outcome, routed-recipient count, duration, failure, and saturation. It contains
no usernames, account IDs, room IDs, message IDs, or content.

This process-local notification is best effort and is not a delivery claim.
Reconnect recovery remains authoritative history; multi-gateway routing remains
M5. The product listener remains unchanged. Rollback removes the handler from
the detached pipeline; durable recall entries remain valid.

## Verification

Handler tests prove authenticated actor/resource binding, sender echo, one
authorized recipient, outsider exclusion, duplicate suppression, malformed
closure, and saturation closure. Disposable PostgreSQL proves room submission,
exact send retry, replacement login, first recall ACK plus sender/member
notification, duplicate recall suppression, and history recovery with immutable
creation sequence plus newer mutation/sync sequence and no UUID exposure.
