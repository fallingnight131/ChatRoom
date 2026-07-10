---
name: evolve-im-protocol
description: Evolve this project's IM protocol and durable messaging model safely. Use for message types or fields, Protobuf or JSON schemas, conversation/message database changes, acknowledgements, delivery/read state, idempotency, ordering, reconnect synchronization, recall, multi-device behavior, file-message metadata, or V1/V2 compatibility.
---

# Evolve IM Protocol

## Read Both Ends

Read `/AGENTS.md`, sections 6-8 of `/docs/architecture/README.md`, and every
producer and consumer of the affected message. For V1 this normally includes:

- `/Common/Protocol.h` and `/Common/Message.*`;
- `/Server/ChatServer.cpp` and `/Server/DatabaseManager.*`;
- `/Client/NetworkManager.*` and the consuming UI/model;
- `/WebClient/src/services/websocket.js` and consuming stores/components.

Do not modify only one client or trust the legacy design document as the schema.

## Preserve Messaging Invariants

- Authenticate the connection and authorize the server-side user and membership.
- Ignore client-provided sender identity, authoritative timestamp, sequence, role,
  and file authorization.
- Accept retries safely through `clientMessageId` idempotency.
- Assign a stable server message ID and per-conversation sequence.
- Synchronize by last contiguous sequence and deduplicate by stable IDs.
- Define accepted, delivered, read, failed, recalled, and expired semantics.
- Preserve order per conversation; do not promise global order.
- Treat transport as at-least-once and make consumers idempotent.

## Version Compatibly

For V1-compatible additions, add optional fields and make old readers ignore
them safely. For changed meaning, required fields, binary encoding, or envelope
changes, create/version V2 instead of mutating V1.

Maintain a compatibility matrix covering:

- old client with compatible new server;
- new client with the supported server range;
- reconnect across deployment;
- duplicate request and duplicate delivery;
- missing, unknown, and oversized messages.

Keep V1-to-V2 translation at the gateway. Generate Java, C++, and TypeScript V2
bindings from one schema source when `/protocol` is introduced.

## Migrate Data Safely

Use expand-migrate-contract:

1. add nullable/new storage and dual-read compatibility where necessary;
2. backfill deterministically and validate counts plus semantics;
3. switch the authoritative read/write path;
4. observe through the rollback window;
5. remove legacy fields only after the compatibility window.

Avoid uncontrolled bidirectional dual writes. Document the source of truth at
every phase.

## Verify

Add serialization fixtures, handler tests, database constraint tests, retry and
reconnect tests, and cross-version integration tests. Test authorization failures
and malformed payloads. Update protocol and architecture documentation in the
same change.
