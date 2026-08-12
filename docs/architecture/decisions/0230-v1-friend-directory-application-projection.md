# ADR-0230: Project the V1 Friend Directory in the Application Layer

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Build the legacy friend-list response through a transport-independent application
service. A durable port supplies one complete bounded authorized snapshot;
compatibility ports translate canonical conversation/account UUIDs to positive
V1 IDs; a separate rebuildable presence port supplies online accounts.

The service returns exactly the fields consumed by Web and Windows: friendship
and friend IDs, username/display name, online state, unread count, peer read
watermark, and pending-request count. It rejects an incomplete/wrong-kind
mapping, duplicate conversation, excessive list, or foreign presence result
instead of returning a partial list that would make clients prune valid caches.

SQL, Netty, and process-local connection state remain outside the service. This
slice activates no route and does not make PostgreSQL authoritative.

## Verification

Application tests cover complete composition, deterministic order, self chat,
online/offline state, and fail-closed incomplete/wrong-kind mappings.
