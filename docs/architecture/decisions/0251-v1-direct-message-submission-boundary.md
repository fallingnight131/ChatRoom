# ADR-0251: Define the V1 Direct-Message Submission Boundary

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define a transport-independent V1 direct text/emoji submission use case. Sender
account and device UUIDs come only from authenticated server state. The target is
an exact, bounded V1 username; the client cannot supply a conversation UUID,
sender identity, authoritative sequence, timestamp, friendship ID, or message
ID. Content remains compatible with the reviewed V1 text/emoji subset and is
bounded to 65,536 UTF-8 bytes. `clientMessageId` remains required and bounded to
128 UTF-8 bytes.

The future PostgreSQL adapter must resolve an active mapped DIRECT relationship
and atomically persist both the canonical message and its positive 32-bit V1
numeric message mapping. Accepted results contain the retained V1 friendship
ID, V1 message ID, canonical sequence/time, exact target, and internal target
UUID. Exact retries return the same result with `duplicate=true`; conflicting
reuse is distinct from authorization and validation failure. Only a first
acceptance may later produce live sender/recipient fan-out.

Do not expose a canonical message UUID in V1 output and do not report success if
the canonical row exists without its V1 mapping. This slice adds no PostgreSQL
adapter or Netty handler.

## Verification

Application tests cover server-bound sender/device propagation, exact target
preservation, text/emoji bounds, invalid ID/message rejection before persistence,
and fail-closed target inconsistency.
