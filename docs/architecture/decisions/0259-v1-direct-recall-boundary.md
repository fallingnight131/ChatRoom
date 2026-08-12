# ADR-0259: Define Owner-Only V1 Direct Recall

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define a transport-independent V1 direct-recall boundary whose only client
identity is a positive signed-32-bit legacy message ID. Bind the actor account
from authenticated server state. Ignore client-supplied peer, friendship,
sender, time, and sequence fields for authorization and result construction.

Future persistence must resolve the FRIENDSHIP mapping and canonical message,
require the actor to be its sender, and use database time for the existing
120-second first-recall window. A first recall additionally requires an active
mapped DIRECT relationship and atomically appends one `MESSAGE_RECALLED` entry
using the next conversation sequence. Its result contains the authoritative
legacy friendship/message IDs, mutation sequence, event time, and resolved peer.

An exact retry by the owner returns the original event with `duplicate=true`,
including after the first-apply window or relationship removal, and must not
emit another live notification. Missing, foreign, expired, unmapped, partially
recalled, or otherwise unauthorized state is one opaque `RECALL_DENIED` result.
Infrastructure failure is not translated into that business result.

This slice adds no PostgreSQL adapter or gateway handler. Rollback removes the
new application types and does not change schema or wire behavior.

## Verification

Application tests prove actor propagation, message-ID bounds, result identity,
and successful projection invariants. PostgreSQL time, ownership, relationship,
atomic sequence allocation, exact retry, and concurrency require adapter tests
in the next slice.
