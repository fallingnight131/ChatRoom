# ADR-0029: Web Conversation Coordinator Boundary

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M2

## Context

The Web Pinia chat store owns room and direct-message view state, but it also
implemented account cache mapping, hydration, snapshot persistence, monotonic
cursor advancement, full/incremental sync selection, optimistic command
creation, reconnect retry, explicit retry, and acknowledgement reconciliation.
The duplicated room/direct orchestration made the 1,500-line store harder to
change safely and tied durable behavior to a UI-state container.

## Decision

- Introduce a transport- and cache-injected `ConversationCoordinator` under the
  Web messaging application boundary.
- Represent targets as `room` or `direct`; map `direct` to the existing
  IndexedDB `friend` cache kind only inside the coordinator.
- Make the coordinator own cache hydration/persistence/eviction, monotonic
  sequence advancement, snapshot-versus-incremental request selection,
  optimistic command staging, restart/reconnect recovery, explicit retry, and
  ACK application.
- Keep Pinia responsible for active view selection, reactive message arrays,
  unread state, UI events, and translating V1 notifications into coordinator
  calls.
- Keep message merge/mutation algorithms and the IndexedDB repository as
  separate, independently tested dependencies.

## Consequences

Room and direct conversations now follow one tested lifecycle and the store no
longer imports persistence or outbox primitives directly. New synchronization
policy can be tested without Vue or a real WebSocket. Event-listener extraction
and restartable browser attachments remain later bounded slices; this decision
does not change the V1 protocol or IndexedDB schema.

## Rollback

The store can restore its previous wrappers without a data migration because
the coordinator preserves the existing cache keys, message shapes, cursor
semantics, transport requests, and stable client message IDs.

## Verification

- coordinator tests cover room/direct cache mapping, persistence and eviction,
  full/incremental request selection, optimistic staging, reconnect recovery,
  explicit same-key retry, ACK application, and monotonic cursor behavior;
- all Web unit tests and the Vite production build pass.
