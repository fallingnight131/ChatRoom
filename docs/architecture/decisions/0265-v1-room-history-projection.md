# ADR-0265: Define the V1 Room-History Projection

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define a transport-independent, UUID-free V1 room-history projection with two
compatible modes: a latest-message page optionally bounded by an exclusive
timestamp, and forward synchronization after a nonnegative room sequence. Bind
the reader account to authenticated server state and resolve a positive
signed-32-bit V1 room ID through active GROUP membership.

Project only reviewed text/emoji messages with positive signed-32-bit V1
message IDs and mapped sender usernames. Fold a canonical recall entry into its
original message: creation `sequence` stays immutable and `syncSequence` is the
later recall sequence when recalled. Project mapped administrative deletion as
a separate `messagesDeleted` event with its shared sequence, operator snapshot,
operation ID, mode, cutoff, count, occurrence time, and positive duplicate-free
V1 message/file IDs. Bound each identity list to 1,000 items.

Sequence pages contain at most 100 combined messages and events, ordered within
each compatible array and across one unique cursor namespace. A continued page
ends exactly at `nextSequence`; a final page advances to the durable high
watermark. Latest pages return creation-ordered messages and no events, keeping
the existing timestamp response compatible.

Missing membership or room is an opaque access denial. Invalid cursors are
distinct. Unknown entry kinds, absent room/message/account/event mappings,
unsupported content, duplicate identities or sequences, oversized deletion
metadata, and unordered or non-advancing output fail the whole read. This slice
adds no PostgreSQL adapter, Netty handler, or product-listener activation.

Rollback removes the unused application boundary and leaves durable data
unchanged.

## Verification

Application tests cover authenticated account propagation, room/count/cursor
bounds, latest versus sequence mode, combined page limits, cursor advancement,
mixed sequence uniqueness, per-array ordering, deletion modes, identity bounds,
and inconsistent projection rejection.
