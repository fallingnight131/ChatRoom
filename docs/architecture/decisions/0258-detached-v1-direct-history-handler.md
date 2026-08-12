# ADR-0258: Compose Detached V1 Direct-History Handling

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0257

## Decision

Compose `FRIEND_HISTORY_REQ` only inside the detached Java V1 compatibility
module. A strict bounded JSON codec accepts the existing exact peer username,
latest `before` timestamp, or `afterSequence` cursor. It preserves the V1 count
policy: absent or non-positive values become 50 and values above 100 become 100.
Malformed numeric types, duplicate fields, unknown data fields, oversized wire
input, or multiple concurrent history requests on one connection close safely.

The handler binds account identity exclusively from authenticated channel state
and executes the application/database read on the bounded compatibility worker,
never the Netty event loop. Completion is discarded if the connection or
authenticated identity changed. Business denial and invalid cursors return a
compatible `FRIEND_HISTORY_RSP`; dependency, encoding, or executor failures
close with a fixed reason. Telemetry records only a fixed outcome, bounded row
count, mode, and duration, never account or conversation identity.

Successful messages expose legacy numeric identity and preserved presentation,
plus creation, mutation, and synchronization sequences. No canonical UUID is
placed in response data. This decision does not enable the V1 module on the
product listener; that remains a separate cutover decision with rollback and
operations gates.

## Verification

Embedded-channel tests prove authenticated binding, count normalization,
sequence response fields, typed business rejection, malformed input,
dependency failure, and executor saturation. Disposable PostgreSQL composition
logs in again after a durable direct send and recovers exactly the message after
the saved sequence cursor through the strict handler.
