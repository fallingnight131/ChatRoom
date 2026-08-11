# ADR-0025: Windows Outbox Application Service Boundary

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M2

## Context

ADR-0024 made Windows room/direct text and emoji submissions restart-safe, but
the policy for creating optimistic messages, persisting pending intent,
authorizing reconnect retry, resolving renamed direct-message peers, and
applying terminal delivery state remained inside the large Widgets
`ChatWindow`. That coupled user-interface code to outbox rules and made the
reconnect and restart behavior difficult to verify without a full GUI.

The change affects message durability, duplicate prevention, reconnect
correctness, client maintainability, and the future attachment command model.
It does not change the V1 wire protocol or server authority.

## Decision

- `OutgoingMessageService` is the Windows application boundary for optimistic
  room/direct text and emoji submissions.
- The service creates the stable `clientMessageId`, constructs the optimistic
  message, persists its `sending` state, reconstructs transport-neutral retry
  commands, and persists `accepted` or `failed` transitions.
- Automatic room retry is returned only for room IDs present in the latest
  authoritative room list. Automatic direct retry is returned only when the
  latest authoritative friend list maps the durable conversation key to a
  current username.
- The service depends on the local conversation repository and message domain
  type. It does not depend on Widgets, `NetworkManager`, JSON envelopes, or V1
  protocol constructors.
- `ChatWindow` remains a compatibility adapter: it converts service commands to
  V1 protocol messages, sends them, projects service state into `MessageModel`,
  and displays errors.
- A missing local repository retains the existing online-only fallback. A
  runtime persistence failure is diagnostic degradation and does not silently
  replace the stable idempotency key.
- Attachment uploads remain outside this service until their restartable
  command model includes source identity, byte offset, authorization expiry,
  finalization state, and cleanup behavior.

## Alternatives Considered

- Keep the policy in `ChatWindow`: rejected because every retry or persistence
  change increases UI-controller coupling and requires broader regression.
- Move V1 JSON construction into the service: rejected because transport
  compatibility belongs at the adapter boundary and must not shape the
  application API.
- Introduce a QObject-based service with network signals: deferred because the
  current synchronous command/result boundary is smaller, deterministic, and
  independently testable. A later synchronization engine may own asynchronous
  lifecycle without changing this command model.

## Consequences

Outbox rules now have a GUI-independent test seam and `ChatWindow` no longer
queries pending SQLite rows or creates idempotency IDs for text sends. Stable
friendship keys remain safe across peer renames. The window still owns history
synchronization and protocol response adaptation; M2 must extract those
separately. Attachment restart requires an additive command schema rather than
forcing file state into the text command.

## Migration and Rollback

The service uses the existing schema-1 message rows and `deliveryState` field;
there is no database or wire migration. Existing unresolved rows are discovered
through the same repository query. Rolling back restores orchestration to the
window without changing stored rows or server behavior.

## Verification

- unit tests cover staging, stable-ID restart retry, membership/relationship
  gating, peer rename resolution, accepted removal, failed exclusion, explicit
  retry, unsupported attachment rejection, and online-only fallback;
- the Qt quality gate builds and runs the new service test with the repository
  restart tests;
- the Windows client Release target compiles in native Windows CI; a macOS build
  remains development evidence only under ADR-0009.
