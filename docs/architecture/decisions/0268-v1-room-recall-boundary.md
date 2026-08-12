# ADR-0268: Define the V1 Room Recall Boundary

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define transport-independent V1 room recall from a server-bound authenticated
actor, positive signed-32-bit V1 room ID, and positive signed-32-bit V1 message
ID. The application boundary accepts no username, timestamp, ownership,
sequence, or notification audience from the wire.

Reserve the existing 120-second owner-only first-apply policy. A future
PostgreSQL adapter must resolve the ROOM conversation and ROOM message mapping,
require enabled actor and active membership, verify that the message belongs to
that mapped room and actor, use database time for the first-apply window, and
allocate one canonical recall sequence atomically. Exact owner retry after a
successful apply must recover the same room/message/mutation identity and
occurrence time with `duplicate=true`, even after the window; it must not
authorize a caller who could not apply the original operation.

Keep opaque room/resource denial separate from ownership/window rejection and
invalid wire identity. Only first apply may later emit `RECALL_NOTIFY`.
Attachment cleanup remains outside this text/emoji boundary. This slice adds no
PostgreSQL adapter, Netty handler, or product-listener activation.

Rollback removes the unused boundary and changes no durable state.

## Verification

Application tests prove authenticated actor propagation, positive signed-32-bit
room/message validation, passthrough of typed denial, exact accepted identity,
and fail-closed rejection if a persistence adapter substitutes either resource.
