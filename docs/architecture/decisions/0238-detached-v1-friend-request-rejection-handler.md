# ADR-0238: Compose Detached V1 Friend-Request Rejection

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Add strict bounded JSON and Netty adapters for the existing
`FRIEND_REJECT_REQ`/`FRIEND_REJECT_RSP` exchange, and compose them after
authenticated pending-request reads in the detached Java V1 module. The handler
uses only the account UUID bound to channel state, dispatches persistence work
through the existing bounded directory executor, permits one mutation in flight,
and suppresses results after disconnect or identity replacement.

First apply and exact duplicate both preserve the V1 `success=true` response.
Authorization or state denial returns the existing generic `success=false` and
Chinese error while leaving the connection usable. Malformed input, executor
saturation, encoding failure, or dependency exception closes with a fixed safe
reason. Telemetry records only fixed outcomes, failure, saturation, and duration;
it never records request or account identifiers. The product listener remains
unchanged.

## Verification

Codec and embedded-channel tests cover exact fields, duplicate-key and invalid-
ID rejection, server-bound identity, first/duplicate/denied responses, unrelated
frame forwarding, late-result suppression, dependency failure, and saturation.
A disposable PostgreSQL integration logs in an imported account, reads request
70, rejects it twice, proves durable REJECTED state, and refreshes an empty list
without exposing canonical UUIDs.
