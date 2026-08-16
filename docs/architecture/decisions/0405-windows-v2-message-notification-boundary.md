# ADR-0405: Windows V2 Message Notification Boundary

Status: Accepted

## Context

The legacy Qt window calls `QSystemTrayIcon::showMessage` directly from several
V1 handlers and may place message content in a desktop notification. The V2
application path previously exposed only a generic `Published` outcome after a
live message was stored. It did not expose stable identity, sender identity, or
mention state to a notification layer, so a UI integration could neither
deduplicate at-least-once delivery nor apply a privacy policy safely.

Windows notifications are a product/platform concern. They must not change
message acceptance, local persistence, synchronization cursors, or protocol
negotiation, and they must not turn history repair into a burst of alerts.

## Decision

Only a successfully validated and locally persisted V2 `MESSAGE_PUBLISHED`
event may produce a notification candidate. ACKs, history pages, reconnect
repair, edits, reactions, pins, search context, protocol failures, and messages
from the authenticated account do not produce candidates.

The controller publishes stable message and conversation identity, sender
identity, and whether the authenticated account was structurally mentioned. It
does not expose message text to the notification boundary. A separate portable
Windows policy owns presentation eligibility:

- remember a bounded set of stable message IDs and suppress duplicates;
- remember an event even when it is suppressed because its conversation is
  currently visible, so a duplicate cannot alert after the window is hidden;
- suppress when the application is active and that exact conversation is
  visible, but allow another conversation to alert;
- use a generic body by default and never copy message text into lock-screen or
  tray presentation; and
- distinguish a structured mention only in the generic title.

The first slice does not invoke a platform API. A later Windows-only adapter
will consume policy decisions, invoke the reviewed notification channel, and
route activation back to the stable conversation ID. That adapter must remain
replaceable and must fail without affecting chat delivery.

The next disconnected slice adds that presenter boundary and extends the
existing tray adapter with a single one-shot activation identity. A newer
notification replaces the older activation target, and a click consumes the
target before routing. That preparatory slice deliberately left it out of the
V2 controller and product configuration.

The product composition now exists behind exact CMake gate
`CHATROOM_ENABLE_WINDOWS_V2_NOTIFICATIONS=ON`, which is invalid without the V2
preview. The build value crosses the immutable product configuration and binary
diagnostic into `ChatWindow`; no writable setting can enable or redirect it.
Ordinary Windows builds do not instantiate the presenter. The isolated feature
candidate compiles the enabled path, but native notification presentation and
click activation still require Windows Release interaction evidence.

## Consequences

The notification decision is deterministic and testable on a macOS development
host, while native Windows behavior still requires a Windows Release interaction
gate. The in-memory duplicate bound survives transient reconnect and resets with
the client process or explicit account teardown; durable message history remains
the source of truth. Generic
notification text is less informative than a content preview, but avoids making
private content exposure the default before notification preferences and Windows
privacy behavior are implemented.

## Rollback

Disconnect the controller candidate signal and remove the policy consumer. This
does not alter the protocol, PostgreSQL, SQLite schema, sync cursor, or stored
messages. Existing V1 tray behavior remains unchanged until its separate
migration.
