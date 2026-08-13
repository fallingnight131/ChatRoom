# ADR-0342: V2 Structured Message Mentions

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

Web and Windows V2 previews now share durable text, reply, reaction, pin, and
edit behavior. Treating every `@name` substring as a mention would make mutable
display names an identity source, create false notifications, and prevent an
offline retry from preserving the user's original target. Adding fields to an
existing Protobuf command without negotiation would also let an older server
silently accept only the plain text and discard mention meaning.

The first mention slice needs stable target identity, exact visible spans,
server-side membership authorization, offline idempotency, edit convergence,
accessible rendering, and safe behavior for peers that do not implement it. It
does not yet need push delivery or a separate notification service.

## Decision

- Add an independently negotiated `MESSAGE_MENTIONS` capability. Allocate
  capability value 4 permanently. A client sends structured mention fields only
  after the server enables that capability; a capable server rejects mention
  fields from a session that did not negotiate it.
- Add a reusable `MessageMention` value containing a canonical target account
  ID plus start and length in UTF-8 bytes. Add repeated mention fields to new
  message, reply, edit, authoritative message record, edit response, and ordered
  edit-event payloads. Existing message type numbers remain unchanged because
  capability negotiation prevents silent downgrade.
- UTF-8 byte spans are zero-based half-open ranges over the exact message body.
  Every span must be nonempty, begin and end on UTF-8 boundaries, start with the
  ASCII `@` byte, be ordered by start, and not overlap another span. Limit one
  body to 20 spans and 10 distinct target accounts. The typed visible label is
  presentation text, not identity or authorization evidence.
- At durable acceptance, PostgreSQL validates every distinct target as an
  enabled active member of the same conversation under the conversation write
  boundary. A target that leaves later remains historical metadata. The
  sender's own account, group-wide mentions, roles as targets, wildcard
  identities, and targets outside the conversation are not valid targets.
- Mention metadata is part of the idempotent submission/edit request. Exact
  retries preserve it; reusing a client message or operation ID with different
  targets or spans conflicts. A changed edit atomically replaces the current
  mention set with the body and revision and carries the resulting set through
  the same mixed conversation sequence.
- Web IndexedDB and Windows SQLite persist mention spans with the authoritative
  message and with pending submission/edit intent. Clients resolve target
  display names separately, render the exact stored span accessibly, and fall
  back to ordinary text if a profile is unavailable. Mention metadata never
  advances a history cursor independently.
- Recall and administrative deletion remove current mention metadata with the
  message body. Privacy erasure of retained edit bodies also erases their mention
  metadata while preserving the bodyless sequence identity needed for cursor
  progress. Metrics and normal logs contain counts and outcomes only, never
  message text, account IDs, or span labels.
- This slice exposes an in-conversation visual mention only. Push, email, badge,
  mention inbox, `@all`, ranking, search indexing, and notification preferences
  require later independently gated work.
- Keep `MESSAGE_MENTIONS` disabled in the gateway and unadvertised by each
  client until protocol bindings, PostgreSQL, history/live, local persistence,
  offline replay, edit integration, accessibility, and compatibility gates for
  that endpoint pass.

## Alternatives Considered

- Parsing `@display-name` only on the server was rejected because display names
  are mutable and non-unique, and retries could resolve to a different account.
- Storing target IDs without visible spans was rejected because clients could
  not render the author's exact text or distinguish ordinary `@` text.
- Storing UTF-16 indices was rejected because JavaScript, Java, and C++ do not
  share one native string-index model. UTF-8 bytes match the bounded wire body.
- Allocating separate command types for every mentioned message and edit was
  rejected because explicit capability negotiation already prevents downgrade
  and separate types would duplicate submission semantics.
- Emitting notifications in the acceptance transaction was rejected because
  notification failure must not weaken durable message acceptance.

## Consequences

Mention meaning survives display-name changes, reconnect, and multi-device
edits, and unsupported peers continue to display ordinary text. Submission and
edit policy gain bounded membership reads and request-digest input. Clients must
maintain byte spans while composing and editing Unicode text; those conversions
belong in tested application helpers rather than view widgets.

## Migration and Rollback

Add nullable/additive mention collections and forward-only PostgreSQL tables or
columns without rewriting existing bodies. Existing messages have an empty
mention set. Before activation, rollback removes capability negotiation and
leaves additive storage unused. After capable messages are accepted, a rollback
server must preserve stored mention metadata and filter it for older clients;
applied migrations are never edited or removed.

## Verification

Require Java/C++/TypeScript golden-wire tests; Unicode boundary, ordering,
overlap, count, distinct-target, canonical-ID, membership, and capability
negative tests; exact/conflicting submission and edit retries; current/edit
history plus live convergence; recall/deletion/privacy cleanup; unsupported
client filtered projection without cursor stalls; Web/Windows restart-safe
outboxes and caches; keyboard-accessible authoring/rendering; and logs/metrics
with no content or target identity.
