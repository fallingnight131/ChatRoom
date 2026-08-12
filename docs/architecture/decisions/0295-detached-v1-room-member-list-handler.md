# ADR-0295: Compose Detached V1 Room Member Listing

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Follows: ADR-0294

## Decision

Compose strict bounded `USER_LIST_REQ` handling in the detached Java V1
compatibility module. Bind the actor from authenticated channel state and accept
only one integral `roomId`; unknown data fields, duplicate fields, fractional
numbers, concurrent requests, executor saturation, dependency failure, or
response overflow close generically rather than returning an authoritative empty
list.

Execute the application query on the bounded directory executor. Preserve the
existing `USER_LIST_RSP` room ID and user fields: `username`, `displayName`,
`isAdmin`, and `isOnline`. Add ignorable `success` and stable `errorCode` fields
for explicit rejection. Bound the response to 1,000 users and 1 MiB. Canonical
account IDs never cross the compatibility boundary.

Compose application presence from the existing process-local V1 connection
registry after PostgreSQL authorization. This is exact for the detached single
gateway only; M5 owns Redis-backed multi-gateway presence. Fixed telemetry
records outcome, count, latency, failure, and saturation without room or account
identifiers.

The product listener remains unchanged. Rollback removes the handler from the
detached pipeline and has no data effect.

## Verification

Codec/handler tests prove actor binding, compatible UUID-free output, stable
business rejection, strict input, dependency failure, saturation, and concurrent
request closure. Disposable PostgreSQL integration proves two authenticated
mapped room members are both projected online with canonical administrator role.
