# ADR-0030: V1 Private-Chat Read Watermark

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M2

## Context

V1 already persists one last-read message ID for each participant in a
friendship, but `MARK_FRIEND_READ` is silent. Senders can only show durable
server acceptance and cannot truthfully show that the peer opened the
conversation. Replacing `accepted` with `read` locally would invent a guarantee.

## Decision

- Keep `MARK_FRIEND_READ` backward compatible and server-authorized.
- Advance the reader's persisted message-ID watermark monotonically to the
  greatest message currently stored for the friendship.
- Add `FRIEND_READ_NOTIFY` with `friendshipId`, `readerUsername`, and
  `lastReadMessageId`, delivered live to the other participant.
- Add `peerLastReadMessageId` to each `FRIEND_LIST_RSP` item so an offline or
  restarted sender recovers the durable peer watermark.
- A client may label only its own messages whose positive server ID is at or
  below that watermark as read.

This is a conversation-level, single-account V1 watermark. It is not a
per-device delivered receipt. Multi-device aggregation and device identity stay
in M6/V2 scope.

## Compatibility and Rollback

Old clients ignore the additive friend-list field and unknown notification.
New clients continue to show `已发送` when connected to an old server. Rolling
back server code leaves the existing last-read columns valid and simply stops
publishing receipts.

## Verification

The V1 friend reliability test verifies authorization through the existing mark
path, live notification fields, and persistence through `FRIEND_LIST_RSP`.
Client tests separately verify monotonic application to own messages only.
