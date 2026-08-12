# ADR-0267: Compose Detached V1 Room History

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Extends: ADR-0266

## Decision

Compose strict bounded V1 `HISTORY_REQ` and `HISTORY_RSP` handling in the
detached Java compatibility module. Bind the reader account to authenticated
channel state; accept only `roomId`, normalized count, optional timestamp bound,
and optional sequence cursor. Execute at most one read per channel off the event
loop and discard a completion if authentication changed.

Encode mapped text/emoji messages with the existing additive sequence, recall,
and client-message fields. Sequence pages also encode mapped
`messagesDeleted` events and authoritative continuation metadata. Bound request
wire size to 4 KiB, response size to 1 MiB, nesting and scalar sizes through the
strict JSON codec, and the application result to 100 combined items. Business
denials return stable error codes without closing. Malformed, concurrent,
saturated, dependency-failed, or encoding-failed work closes generically.
Telemetry contains only outcome, item count, mode, duration, failure, and
saturation; it contains no message, account, or room data.

This handler provides recovery semantics rather than live-delivery proof. It
does not activate the product listener. Rollback removes the handler from the
detached pipeline; the additive application and persistence boundaries remain.

## Verification

Codec/handler tests prove authenticated actor binding, count normalization,
mixed message/deletion encoding, stable business denial, and malformed or
saturated fail-closed behavior. Disposable PostgreSQL proves login, durable
room submission, exact retry suppression, replacement login, and recovery of
the message after the prior sequence without exposing UUIDs.
