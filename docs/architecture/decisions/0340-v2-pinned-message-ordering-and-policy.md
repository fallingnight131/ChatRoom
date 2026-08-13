# ADR-0340: V2 Pinned Message Ordering and Policy

## Status

Accepted

## Context

Pinned messages are shared conversation state, not a client-local bookmark.
They must converge across devices, survive reconnect, respect conversation
authorization, and remain ordered relative to messages, recall, deletion, and
reactions. A copied message body would become stale and could retain content
after recall or administrative deletion.

## Decision

- Add an explicit `MESSAGE_PINS` handshake capability. Clients and the gateway
  must not send pin payloads unless it was negotiated.
- Permanently allocate type 109 for `SetMessagePin`, type 112 for the correlated
  `MessagePinApplied` response, and type 113 for the uncorrelated
  `MessagePinChanged` event. Types 110 and 111 remain the existing conversation
  directory contract.
- A command carries canonical conversation/message IDs, a desired `pinned`
  boolean, and a client-generated operation ID. The authenticated server binds
  actor identity and device identity.
- Exact retries return the original result. Reusing an operation ID with
  different input is an idempotency conflict. A convergent no-op has
  `changed=false` and sequence zero; only a changed projection consumes the next
  conversation sequence and enters mixed history/live delivery.
- Store at most 50 active pins per conversation. An activating operation that
  would exceed the bound is rejected without consuming a sequence. Removing a
  pin remains allowed.
- Active conversation members may read pins. In direct conversations either
  active participant may change them. In groups only active OWNER or ADMIN
  members may change them. Authorization and target availability are checked
  in the same serialized transaction as the mutation.
- Durable state stores message identity, actor identity, and timestamps but no
  copied message body. Recall or administrative deletion automatically removes
  an active pin through an ordered server-authored pin-change entry in the same
  mutation transaction; physical target deletion must not leave a dangling pin.
- Clients persist bounded optimistic operations before sending, but ACKs never
  advance the history cursor. Contiguous mixed history is the sole durable
  cursor authority. Failed operations remain visible for explicit retry.

## Consequences

The feature can reuse the reaction operation, mixed-history, capability, and
offline-convergence patterns without silently treating local bookmarks as
shared state. Automatic unpin on recall/deletion adds transaction work but
prevents stale or privacy-sensitive pinned content. Search, notification,
multiple pin categories, pin notes, and local-only bookmarks remain separate
features.

## Verification

Require generated Java/C++/TypeScript compatibility; PostgreSQL exact-retry,
permission, 50-pin bound, concurrency, recall/deletion cleanup, and restart
tests; gateway capability/history/live filtering; and Web/Windows offline,
failure, reconnect, accessibility, and cursor-gap tests before either client
advertises `MESSAGE_PINS`.
