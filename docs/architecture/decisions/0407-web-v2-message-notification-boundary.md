# ADR-0407: Web V2 Message Notification Boundary

- Status: Accepted
- Date: 2026-08-16
- Owners: project maintainers
- Related milestone: M6

## Context

The supported Windows V2 candidate separates persisted remote-live message
candidates from native presentation. The Web V2 preview has no equivalent
boundary. Showing a browser notification from snapshot changes would confuse
history repair, optimistic acceptance, and live delivery; could repeat
at-least-once events; and could expose message content on a lock screen.

Browser notification permission requires a user gesture and can be denied,
revoked, or unavailable. Browser API failure must never affect message
persistence, sequence progress, reconnect, or the visible chat path.

## Decision

- Only a validated remote `MESSAGE_PUBLISHED` event successfully saved to the
  account/conversation IndexedDB snapshot may become a candidate. ACKs,
  history/context pages, reconnect repair, optimistic sends, mutations,
  self-authored live echoes, and failed cache writes never become candidates.
- A detached bounded policy remembers stable message IDs, suppresses duplicates,
  and remembers an event even when the active visible conversation suppresses
  presentation. Another conversation may notify while the page is active.
- The policy carries stable message/conversation/sender/account identity and a
  structural mentioned-account boolean. It never receives message text.
- The browser presenter uses localized generic title/body copy, requires granted
  permission, isolates constructor/close/activation failures, and consumes one
  stable conversation activation per notification click.
- Composition uses the exact default-false `VITE_CHAT_V2_NOTIFICATIONS` Web
  build flag. The application emits a detached candidate only after its
  IndexedDB save resolves successfully; duplicate live events, self echoes,
  history pages, disabled builds, false saves, and stale sessions remain silent.
  Permission must be
  requested by a native user action with visible state and a disable path. This
  decision adds no service worker, Web Push, closed-browser delivery, server
  notification service, or delivery guarantee.
- The candidate view persists only a non-secret boolean choice. It restores the
  choice only while browser permission is still granted, fails closed when
  permission is revoked, and reports session-only behavior when browser storage
  is unavailable. The enable button is the only permission-request call site.
- A notification click focuses the existing page and opens the already validated
  stable conversation ID. Generic localized title/body copy contains no sender,
  conversation name, account ID, or message text.

## Consequences

The deterministic policy and platform seam can be tested without host
notification permission. Generic copy is intentionally less informative than a
content preview but safer before per-conversation preferences and lock-screen
controls exist. In-memory deduplication resets on account teardown or page
restart; IndexedDB remains recoverable message truth, not a notification queue.
Browser permission and the local preference do not imply background or closed-
browser delivery.

## Verification

Unit tests cover bounded eviction, duplicate and visible-conversation
suppression, structured mention classification, malformed/self rejection,
denied/revoked permission, storage denial, platform failure, privacy-safe copy,
and one-shot stable conversation activation. Application tests additionally
require save-before-candidate ordering and silent duplicate/self/history/failed-
save paths. Deterministic Chromium and Firefox tests prove no startup prompt,
explicit enable/disable persistence, generic mention copy, duplicate
suppression, and click navigation. Deployment evidence remains separate.

## Rollback

Rebuild without `VITE_CHAT_V2_NOTIFICATIONS=true` and remove the detached
candidate subscription, policy/presenter, and tests. No protocol, PostgreSQL,
IndexedDB schema, message state, permission, or product route needs migration.
