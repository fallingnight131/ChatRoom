# ADR-0330: Web V2 Offline Reply Composition

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The Web V2 preview already owns bounded optimistic messages, an isolated
IndexedDB conversation cache, ACK reconciliation, reconnect history repair, and
ordered outbox replay. Reply composition must reuse those guarantees. Storing a
quoted body separately would create stale or privacy-inconsistent content after
target recall/deletion, while treating a reply as transient UI only would lose
its semantics during offline recovery.

## Decision

- Store an optional reply identity on each V2 cached message: target message
  UUID, target conversation sequence, and target sender account UUID. Store no
  target content or display-name snapshot. Existing cache rows normalize to no
  reply, so the additive structured-clone shape requires no IndexedDB version
  upgrade.
- Keep the currently selected reply target in view memory only. A new reply may
  target only an accepted, available message in the active conversation.
- Create one optimistic message with one client-generated message ID. Initial
  send, explicit retry, and reconnect replay all dispatch type 105 with the same
  client ID, target UUID, and content. Transport/protocol failure marks the same
  item failed instead of downgrading it to a plain message.
- Merge history/live `MessageRecord.reply` into the optimistic item by stable
  server or client identity. Cache and snapshots clone nested reply metadata so
  views cannot mutate application truth.
- Mark recalled targets unavailable and remove deleted targets through the
  existing ordered mutation stream. Rendering resolves the body only from the
  current normal message cache; absent and recalled targets use explicit states.
- Add keyboard-operable Reply and Cancel controls, restore focus to the composer,
  and expose reference state as accessible text. Keep all behavior behind the
  existing default-off Web V2 product gate.

## Consequences

Offline and ACK-loss recovery preserve reply meaning without a second outbox or
quote-content store. A reply can appear before its target history range; the UI
shows an unavailable placeholder until ordinary synchronization supplies it.
Server validation remains authoritative if cached state is stale.

Windows remains pending and must implement the same product behavior over its
own Qt/local-storage boundaries rather than sharing Web presentation code.

## Verification

- protocol tests cover type-105 encoding and fail-closed reference validation;
- application tests cover optimistic creation, durable cache shape, fixed target
  and client ID on retry, recall state, and authoritative record merge;
- cache tests cover exact large target sequences and exclusion of unrelated
  secret/media fields;
- static accessibility policy covers keyboard reply/cancel and unavailable
  rendering; the full Web test and production build gates pass.

## Rollback

Hide/remove the reply controls and stop dispatching type 105. Older code ignores
the additive cache keys, and the sanitizer can later discard them after the
compatibility window. No V1 database or Web deployment schema rollback is
required.
