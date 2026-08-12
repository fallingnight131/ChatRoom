# ADR-0255: Define the V1 Direct-History Projection

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define a transport-independent, UUID-free V1 direct-history projection with two
compatible modes: a latest-message page optionally bounded by an exclusive
timestamp, and forward synchronization after a nonnegative conversation
sequence. Bind the reader account to authenticated server state and resolve one
exact target username to an active mapped DIRECT relationship.

Project only reviewed text/emoji messages that have positive signed-32-bit V1
message IDs and mapped sender usernames. Fold a canonical recall entry into its
original message: `sequence` remains message creation order,
`mutationSequence` is the recall entry order, and `syncSequence` is their
maximum. Sequence pages are strictly ascending by `syncSequence`, return at
most 100 records, and expose server-derived `nextSequence`, `lastSequence`, and
`hasMore`. Latest pages select the newest bounded records but return them in
ascending creation order.

Missing membership, target, or friendship is an opaque access denial. Invalid
cursor/request input is distinct. Missing V1 message/account mappings, unknown
entry kinds, partial recall state, duplicate IDs, unordered output, or a result
beyond the continuation cursor fail closed instead of silently skipping data.
This slice adds no PostgreSQL adapter or Netty handler.

## Verification

Application tests cover authenticated account propagation, request/cursor
bounds, target/mode consistency, result size, duplicate identity, and strict
sequence ordering.
