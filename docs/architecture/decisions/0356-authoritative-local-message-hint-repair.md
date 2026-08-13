# ADR-0356: Authoritative Local Message Hint Repair

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0355 stops the Redis cursor when local repair fails, but the process-local
router previously tracked only a set of subscribed conversations. Directly
broadcasting a Redis hint would trust reconstructable data for message content,
membership, and duplicate suppression.

The current transactional outbox contains only new-message events. Other mixed
conversation entry kinds are not yet written to the outbox, so this first local
repair slice is intentionally exact for messages and must not pretend to handle
reaction, pin, edit, recall, or deletion hints.

## Decision

- Record the last server-side message sequence observed by each local
  channel/conversation when an authorized final history page subscribes and when
  live message delivery succeeds.
- For a message hint newer than that local sequence, query PostgreSQL from
  `hint.sequence - 1` with limit one using the account bound to the authenticated
  connection. Never use identity from Redis or the original submitting client.
- Require PostgreSQL to return the exact hinted sequence and stable message UUID
  before encoding and publishing server-truth content. Any missing/mismatched
  event fails the hint and preserves the preceding Redis cursor.
- An equal/older hint is duplicate for that connection and writes no socket.
  Current membership denial removes the conversation from that connection and
  removes the process route when it has no remaining subscribers.
- Apply the same capability filtering for mentions and forwarding as ordinary
  local live message delivery. An inactive or slow connection is removed/closed
  through the existing bounded router policy.
- A process-local live sequence can advance over a gap; clients already detect
  that gap using their last contiguous durable cursor and repair from PostgreSQL.
  The server-side sequence exists to suppress repeated hints, not to replace the
  client's contiguous cursor.

## Consequences

Redis cannot inject content or authorize recipients. A partially processed hint
is safe to retry: channels already delivered the exact sequence classify it as
duplicate, while remaining channels retry authoritative lookup. Membership
revocation converges on the next hint even before route lease expiry.

This implementation performs one bounded SQL read per behind connection. A
future measured optimization may group active subscribers by account while
preserving per-channel capability and sequence state. Mixed event kinds require
atomic outbox writers and corresponding authoritative encoders before activation.

## Verification

Router tests prove account-bound SQL reauthorization, exact ID/sequence loading,
capability-preserving publication, per-connection duplicate suppression, no SQL
read for duplicates, membership-denial route removal, and fail-closed identity
conflict without socket output.

## Rollback

Leave `LocalConversationMessageHintRepairAdapter` and the Redis consumer
uncomposed. The existing process-local publish path remains unchanged.
