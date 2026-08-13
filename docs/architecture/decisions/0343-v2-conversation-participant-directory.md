# ADR-0343: V2 Conversation Participant Directory

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

ADR-0342 gives mentions stable account targets and exact visible UTF-8 spans,
but the current V2 client contract exposes only account IDs on message records.
It does not expose the active participants or their current display names. A
client-side mention picker built from message history would omit members who
have not spoken and would present opaque UUIDs for group participants. Parsing
typed display names would reintroduce mutable, non-unique identity as authority.

The participant lookup must remain bounded, require current conversation
membership, avoid timestamp pagination over mutable profile data, and stay off
for clients that have not completed the structured-mention gate.

## Decision

- Permanently allocate V2 message types 117 and 118 to
  `LIST_CONVERSATION_PARTICIPANTS` and `CONVERSATION_PARTICIPANT_PAGE`.
- Accept a conversation ID, an optional exclusive `after_account_id` cursor,
  and a limit in 1..100. Return active participants ordered by canonical account
  ID ascending. The page cursor is the last returned account ID; display-name or
  role changes therefore cannot reorder a scan.
- Return canonical account ID, current display name, and conversation role for
  each participant. Include the requester so the response is a complete member
  directory; clients exclude self from mention choices because ADR-0342 forbids
  self mentions.
- Authorize the query from the authenticated session identity. PostgreSQL must
  first establish that the requester is an active member of the conversation
  and then return only active members. A nonmember receives the existing fixed
  `NOT_AUTHORIZED` response with no existence or membership detail.
- Dispatch the bounded read off the gateway event loop. Do not add a database
  migration: the source of truth remains `conversation_member` joined to
  `account`. Emit only fixed-cardinality outcome metrics; normal logs contain no
  participant IDs or display names.
- Accept the command only on a session that negotiated `MESSAGE_MENTIONS`.
  Web and Windows request the directory after opening a conversation, keep the
  result account/conversation scoped, and refresh it on a new session or an
  explicit user retry. It is a discovery cache, never membership authority.
- Mention composition inserts the selected current display name into ordinary
  text while retaining the selected account ID and recalculating UTF-8 spans.
  Sending remains possible without loading this directory, but no structured
  mention may be inferred from raw `@text`.

## Alternatives Considered

- Deriving candidates from message senders was rejected because silent and new
  participants would be missing and display names are absent from message
  records.
- Adding a sender display-name snapshot to every message was rejected because
  it still cannot discover silent members and increases every history/live
  payload.
- Ordering by display name was rejected because profile changes destabilize
  pagination and duplicate display names need an identity tie-breaker anyway.
- Reusing the unrestricted account/contact search surface was rejected because
  it would expose identities outside the authorized conversation and would not
  prove active membership.

## Consequences

Clients gain a complete, human-readable mention source without making display
names authoritative. One bounded SQL query and two protocol types are added to
the modular monolith. Very large groups require multiple pages; clients may
incrementally present results and must not assume the first page is complete.

## Migration and Rollback

The protocol addition is command-only and requires no stored-data migration.
Old clients never send the new type. Before Web or Windows capability activation,
rollback removes handler registration with no durable compatibility impact.
After activation, rollback must disable client capability 4 first; stored
message mention metadata remains governed by ADR-0342.

## Verification

Require Java/C++/TypeScript binding tests for types 117/118; payload policy tests
for UUIDs, page bounds, ordering, cursor identity, UTF-8 display names, and roles;
PostgreSQL tests for active requester authorization, inactive-member exclusion,
stable pagination, and profile-name projection; gateway tests for capability,
identity binding, fixed denial, and off-event-loop execution; and Web/Windows
tests for account/conversation cache isolation, self filtering, pagination,
retry, keyboard selection, Unicode insertion, reconnect, and fallback behavior.
