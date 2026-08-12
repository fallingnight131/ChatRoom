# ADR-0241: Compose Detached V1 Friend-Request Acceptance and Notification

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Compose strict bounded `FRIEND_ACCEPT_REQ` handling after pending-request reads
in the detached Java V1 module. Accept the legacy optional `fromUsername` string
for Windows/Web wire compatibility, but never use it for identity or routing.
The authenticated recipient comes from channel state and the durable requester
comes from the PostgreSQL acceptance result.

Run one acceptance per connection through the bounded directory executor and
suppress late results after disconnect or identity replacement. First apply and
exact duplicate both return the existing `FRIEND_ACCEPT_RSP success=true`; only
first apply schedules `FRIEND_ACCEPT_NOTIFY` to the requester's current active,
authoritative process-local V1 channel. Replacement and stale-close checks are
owned by the connection registry. Duplicate retries never repeat notification.

Ordinary authorization/state denial returns the existing generic failure while
keeping the connection usable. Malformed input, saturation, encoding failure,
or dependency failure closes with a fixed safe reason. Fixed telemetry records
first-route-scheduled, first-no-local-route, duplicate, rejected, failed,
saturated, and elapsed time without account/request identifiers. This is single-gateway compatibility;
M5 owns distributed routing. The product listener remains unchanged.

## Verification

Codec and embedded-channel tests cover the ignored legacy hint, invalid and
duplicate fields, exact response/notification fields, first-only notification,
domain denial, malformed input, dependency failure, and saturation. Registry
tests cover active replacement and stale-close safety. Disposable PostgreSQL
verification logs in both imported participants, accepts a newly inserted
request using a spoofed hint, observes notification only on the durable
requester's connection, retries without another notification, and verifies
durable acceptance.
