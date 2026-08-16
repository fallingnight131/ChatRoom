# ADR-0404: Conversation Message Search Foundation

- Status: Accepted
- Date: 2026-08-16
- Owners: project maintainers
- Related milestone: M6

## Context

Web and Windows need message search, but the server remains authoritative for
conversation membership, recall, deletion, and current edited content. Searching
only a client's partial offline cache produces incomplete and potentially stale
results. Introducing Elasticsearch/OpenSearch before measured PostgreSQL query
evidence would add another durable-looking copy, an asynchronous privacy path,
and substantial operations work without a demonstrated need.

Chinese and mixed-language chat also make English-oriented stemming an unsafe
default. The first slice needs literal behavior that users can predict and a
cursor that does not confuse search ranking with conversation order.

## Decision

- Add a distinct, capability-gated V2 conversation-message search command and
  response. Allocate capability 6 and message types 126 and 127; older clients
  do not negotiate them and V1 is unchanged.
- Scope the first product slice to one exact conversation. The authenticated
  account must be an active member at query time; client-supplied sender or
  membership information is never accepted.
- Accept one stripped UTF-8 literal query of 1..128 bytes and a result limit of
  1..50. Do not interpret regular expressions, wildcard characters, markup, or
  query-language operators.
- Return at most one current text projection per message, ordered by
  `conversation_sequence DESC`. `before_sequence=0` starts from the current
  tail; a positive value is an exclusive descending cursor. Results carry the
  stable message ID and sequence so clients can deduplicate and open bounded
  history around the hit.
- Search only current UTF-8 text. Exclude deleted and recalled messages. Edits
  replace the searchable body; reply, mention, reaction, pin, and forwarding
  metadata do not independently affect matching.
- Make pagination current-state rather than snapshot-isolated across user
  requests. A concurrent edit/recall can add or remove a later page hit; clients
  deduplicate by stable message ID and must reauthorize when opening context.
- Start with a PostgreSQL adapter behind an application port. Use literal
  case-insensitive substring semantics and a rebuildable PostgreSQL index only
  when the migration and query plan prove it helps the supported data set.
  PostgreSQL messages remain truth.
- An external search service is a later ADR-only optimization. It must be
  asynchronously rebuildable from PostgreSQL, checkpoint by durable
  conversation sequence, enforce authorization again at query/open time, and
  remove recalled/deleted content within a documented privacy window.

## Consequences

The first slice gives Web and Windows identical search semantics without
claiming global ranking, stemming, fuzzy matching, or immediate external-index
consistency. Per-conversation sequence order is predictable and allows a result
to reconnect to ordinary history synchronization.

Literal substring search may become expensive for very large conversations,
especially for one- or two-code-point terms. The adapter must bound results,
record fixed-cardinality latency/outcome metrics, and retain `EXPLAIN` evidence
before changing indexes or introducing another service. Query text,
conversation IDs, and account IDs must not be metric labels or ordinary logs.

## Verification

- Lock Java, TypeScript, and C++ generated-wire compatibility for capability 6
  and message types 126/127.
- Test empty, oversized, malformed UTF-8, wildcard-looking, Unicode, and maximum
  queries plus invalid limits/cursors.
- Test active-member success; outsider, former-member, dissolved-conversation,
  recalled, deleted, and non-text exclusion; edited-body replacement; descending
  paging; and stable message identity.
- Test old clients without capability 6, a capable client against a server with
  the feature disabled, disconnect/retry, and response correlation.
- Record bounded PostgreSQL query-plan and latency evidence before a production
  index or external engine is selected.

Implementation evidence now includes a strict default-false
`CHATROOM_GATEWAY_MESSAGE_SEARCH_ENABLED` composition gate. Exact `true`
installs the PostgreSQL handler and permits capability 6 negotiation only for a
requesting client. Disposable PostgreSQL verification drives that candidate
through real TLS/WSS authentication and active-membership authorization; both
ordinary product-client builds remain disabled. Web and Windows now have
independent immutable candidate gates. The Windows value is carried through
the product composition into exact ordered session negotiation and is exposed
by side-effect-free binary diagnostic schema 3; no query or UI is enabled by
that seam alone.

## Rollback

Stop negotiating capability 6 and remove the Web/Windows search entry points.
Keep the permanent wire values reserved. A rebuildable database index may be
removed independently; canonical messages and ordinary history are unchanged.
