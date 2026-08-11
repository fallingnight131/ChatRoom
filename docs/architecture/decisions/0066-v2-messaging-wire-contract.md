# ADR-0066: V2 Messaging Wire Contract

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The application/PostgreSQL message boundary was implemented but deliberately
unreachable from the gateway. Exposing it requires permanent message-type
numbers, generated payloads, cursor semantics, strict structural bounds, and
cross-language evidence before any dispatch code depends on the wire shape.

## Decision

- Permanently allocate V2 message types 100 `SubmitMessage`, 101
  `MessageAccepted`, 102 `ReadMessageHistory`, and 103 `MessageHistoryPage`.
  Commands use command envelopes and results use response envelopes.
- A submit carries canonical conversation UUID, positive content type, and at
  most 1,000,000 content bytes. Its stable idempotency key is the envelope's
  required `client_message_id`; the authenticated account/device come only from
  server-bound connection state.
- An accepted response carries server message/conversation UUIDs, positive
  conversation sequence, PostgreSQL-authoritative acceptance time, and explicit
  duplicate flag. Acceptance means durable commit, not delivery/read state.
- A history command carries canonical conversation UUID, nonnegative signed-
  server-range cursor, and limit 1..100. A page contains at most 100 ascending
  records, an explicit next cursor, conversation high watermark, and `has_more`.
- Permanently add protocol error codes for idempotency conflict and opaque not-
  authorized denial. Do not encode account/membership/device failure detail.
- Reserve 1 MiB for the outer envelope payload but cap inner message content at
  1,000,000 bytes so Protobuf identifiers/metadata fit the transport bound.
- Generate Java, C++, and TypeScript from the same schema and extend the fixed
  `SubmitMessage` golden payload across all three bindings before gateway use.

## Consequences

- Web and Windows can implement one stable optimistic-send and cursor-sync
  contract without depending on PostgreSQL row shapes.
- `content_type` is a separate positive registry whose concrete content schemas
  must be added before product exposure; unknown values will be rejected by the
  later application dispatcher.
- This slice still installs no messaging handler, so supported clients and V1
  behavior are unchanged.

## Verification

Java tests cover permanent kinds, canonical UUIDs, identifier/content/page
bounds, signed-range rejection, ordered response pages, and fixed wire bytes.
TypeScript decodes/re-encodes the same payload, and the generated C++ messaging
source is compiled and performs the same golden round trip.

## Rollback

Remove the unconsumed schemas, policy, registry values, and tests before any V2
client release. Once a client release uses these numeric values, reserve rather
than reuse them.
