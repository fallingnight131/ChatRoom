# ADR-0306: Preserve imported direct attachments in V1 history

- Status: Accepted
- Date: 2026-08-13

## Context

V1 friendship attachment messages share the same durable sequence as direct
text and emoji messages. The Java direct-history projection previously accepted
only canonical text messages, so a valid imported attachment made the complete
friend history fail closed. Skipping it would advance the synchronization cursor
past visible history and break reconnect correctness.

The legacy direct-message wire format also exposes friendship file identifiers
as negative numbers, unlike room file identifiers. PostgreSQL deliberately
stores the typed source identifier as a positive number.

## Decision

Project canonical direct attachment messages into `FRIEND_HISTORY_RSP` only when
the message, FRIENDSHIP message mapping, FRIENDSHIP file mapping, sender mapping,
same-conversation attachment, and active mapped participants are complete. The
legacy content type must be `file`, `image`, or `video`; canonical attachment
state must be `READY` or `UNAVAILABLE`.

The application model keeps the positive source file identity. The V1 gateway
alone translates it to the historical negative wire `fileId`. Return safe file
name and size, content type, message identity, sequence and timestamp. Use the
file name as message content. `UNAVAILABLE` returns `fileCleared=true` and its
bounded reason. Never expose UUIDs, object keys, hashes, MIME evidence, provider
URLs, local paths, or upload authorization.

Pending, revoked, partially mapped, type-inconsistent, or out-of-range rows make
the complete history request fail rather than silently skipping a sequence.

## Consequences

- Existing Web and Windows V1 readers keep the legacy negative friendship file
  identity and mixed-message reconnect ordering.
- The positive durable identity remains unambiguous inside typed PostgreSQL
  mappings; the V1 sign convention does not leak into application persistence.
- This read path grants no download authorization. Object access remains a
  separately authenticated attachment operation.
