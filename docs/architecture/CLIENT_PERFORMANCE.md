# Client Performance Policy

This document records client-side performance boundaries. It defines mechanisms
and verification expectations; it does not make an unmeasured latency or
capacity claim.

## Web Conversation Timeline

The Web conversation repository retains at most 500 message records per
account-scoped conversation. Retention and rendering are separate limits:

- conversations with at most 80 messages render normally;
- longer conversations render only the viewport plus 700 CSS pixels of
  overscan on either side;
- top and bottom spacers preserve the full scroll range;
- text and attachment messages begin with conservative height estimates and a
  `ResizeObserver` replaces those estimates with measured wrapper heights;
- prepending a history page restores the previous viewport by the change in
  scroll height, rather than jumping to the newly loaded first item;
- new messages scroll automatically only while the reader is already near the
  bottom.

The pure window calculation is covered by source-independent unit tests for
short lists, variable heights, invalid dimensions, end-of-list behavior, and a
500-message retained conversation. Browser interaction and memory measurements
remain release evidence to add before making a user-visible performance claim.

## Protocol Semantics Boundary

Delivery/read presentation follows the server-authoritative semantics below; it
is never inferred from local rendering. Device-aware aggregation remains an M6
protocol concern.

## Web Media Persistence

IndexedDB conversation snapshots are a metadata cache, not a media cache. They
persist attachment identity, name, type, size, and conversation ordering, but
persist zero attachment or thumbnail bytes. They also reject temporary upload
and download URLs, authorization values, `File`, `Blob`, and byte-buffer fields.

Database version 2 sanitizes existing version-1 snapshots during upgrade, and
the load/write boundary sanitizes records defensively as well. Media available
in the active page may be rendered from memory; after a reload, users open the
attachment through a fresh server-authorized HTTP request. A future thumbnail
cache requires its own global byte budget, eviction policy, access revocation,
and tests instead of adding byte fields back to conversation records.

Pre-cutover V2 snapshots use a separate version-1 `chat-room-client-v2`
database. They retain at most 500 accepted text-message records plus 100
unresolved outbox records per account/conversation UUID and serialize server
sequences as canonical decimal strings, preserving
the signed 64-bit range without JavaScript `Number` rounding. The store accepts
no resume tokens, authorization values, byte buffers, or temporary URLs. Its
physical separation prevents a V2 experiment from raising the live V1 database
version and breaking asset rollback.

## Windows Conversation Timeline

The Windows client uses Qt's model/view rendering boundary: `QListView` requests
variable row sizes and paints visible messages through `MessageDelegate`; it
does not allocate one persistent widget per message. `MessageModel` retains at
most 500 resolved messages per conversation, matching the SQLite repository
boundary. It may additionally retain unresolved text, emoji, and attachment
sends because removing those would hide user work that still requires retry or
cancellation.

The limit is enforced after live append, authoritative replacement, history
prepend, synchronization-page reconciliation, and optimistic acceptance. When
an unresolved send becomes accepted, the oldest resolved row is evicted. Model
tests cover the resolved bound and preservation of unresolved rows. Native
Windows interaction and memory measurements remain M4 release evidence; a
successful macOS development build is not that evidence.

## Outgoing State Presentation

Web and Windows render `发送中`, `发送失败`, and `已发送` for the sender. `已发送`
means only that an authoritative server message ID exists after durable
acceptance. It must not be translated or styled as `已送达` or `已读`. Those
states require an additive protocol event and persistent peer/device watermarks.

The Web private-chat client consumes ADR-0030's monotonic peer read watermark.
It upgrades only the authenticated user's positive-ID messages through the
watermark to `已读`, restores the watermark from the friend list after reload,
and persists the resulting presentation in the metadata-only conversation
snapshot. Older servers simply leave the existing `已发送` state unchanged.

The Windows client applies the same rule in `MessageModel`, persists `Read` in
the existing integer delivery-state column, and restores the peer watermark
from the friend list. Live `FRIEND_READ_NOTIFY` events and recovered fields are
merged monotonically; an old server therefore degrades to `已发送` without a
local schema migration.
