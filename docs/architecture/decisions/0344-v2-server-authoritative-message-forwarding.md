# ADR-0344: V2 Server-Authoritative Message Forwarding

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M6

## Context

V2 can durably submit, reply to, edit, and synchronize text messages, but it has
no safe forwarding contract. Letting a client copy a visible body into an
ordinary submission would make the client the authority for source content,
lose provenance, race concurrent edits or recall, and accidentally carry reply
or mention meaning into a conversation where those identities may not exist.

The first forwarding slice needs retry safety, source and destination
authorization, edit-race detection, old-client compatibility, and a useful
presentation marker. It does not yet need bundles, comments, multi-target
transactions, or attachment copying.

## Decision

- Allocate `MESSAGE_FORWARDING` capability value 5 and `FORWARD_MESSAGE` type
  119 permanently. The command contains canonical source conversation and
  message IDs, the expected current source content revision, and one canonical
  destination conversation ID. Its envelope `client_message_id` is the
  idempotency key for the newly created destination message.
- A server accepts the command only for a session that negotiated capability 5.
  It binds the actor and device from the authenticated session, verifies active
  read membership in the source conversation and active write membership in
  the destination, then reads the current source from durable PostgreSQL truth.
  Missing, recalled, deleted, inaccessible, or non-text sources fail opaquely.
  A changed source revision returns the existing stable revision-conflict code.
- The first slice forwards exactly one target per command. A multi-select client
  creates one command and a distinct client message ID per target. This gives
  every target independent retry and failure semantics and avoids a partially
  committed cross-conversation transaction.
- The destination is a new ordinary message authored by the forwarding actor,
  with a new server message ID, destination sequence, server timestamp, and
  content revision zero. The server copies only the current validated UTF-8
  text body and content type. It strips reply references and mention spans so a
  forward cannot leak source membership or trigger destination notifications.
- Add `forwarded = 14` to `MessageRecord` as presentation metadata. Destination
  peers receive only this marker, not source conversation, source message,
  original sender, or source timestamp identities. Old clients safely render
  the copied body as ordinary text. Source identities are used during command
  authorization and idempotency comparison but are not persisted as public
  destination-message provenance.
- Exact retries return the original `MessageAccepted` result. Reuse of the
  envelope client message ID with a different source, expected revision, or
  target is an idempotency conflict. Acceptance, destination sequence
  allocation, copied body, marker, and idempotency outcome commit atomically.
- Capability 5 remains unadvertised by gateway, Web, and Windows product paths
  until PostgreSQL authority, history/live projection, local cache/outbox,
  reconnect, UI, accessibility, abuse controls, and compatibility gates pass.
  Metrics contain fixed outcomes and latency only; logs contain no body or
  conversation/message/account identifiers.

## Alternatives Considered

- Client-side copy followed by `SubmitMessage` was rejected because stale or
  fabricated text would be indistinguishable from an authorized forward.
- Exposing source IDs to destination clients was rejected because it leaks
  cross-conversation identity and encourages clients to dereference content
  they are not authorized to read.
- Copying mention and reply metadata was rejected because their targets are
  scoped to the source conversation and may cause incorrect navigation or
  notifications.
- One command with many targets was rejected for this slice because partial
  authorization and storage failure would require a durable batch result and
  recovery model before it improves the user experience.
- Forwarding attachments immediately was deferred until the attachment owner,
  object-reference lifetime, malware state, and destination authorization
  rules are designed together.

## Consequences

Clients cannot spoof the forwarded marker or choose stale source text, and each
destination retry converges on one durable message. The gateway and messaging
module gain a cross-conversation authorization operation, and forwarding an
edited source requires an explicit client refresh/retry. Stripping origin
identity favors privacy and broad compatibility over source navigation.

## Migration and Rollback

The Protobuf command, capability, and record flag are additive. PostgreSQL will
add forward request metadata and the destination marker through a new
forward-only migration without rewriting existing messages; old rows read as
`forwarded = false`. Before activation, rollback removes runtime registration
and capability advertisement while leaving additive schema unused. After
activation, rollback must continue to preserve and project the marker, even if
new forward commands are disabled.

## Verification

Require fixed Java/TypeScript/C++ wire fixtures; canonical-ID, revision, payload,
capability, and envelope idempotency bounds; source-read and destination-write
authorization denial; recall/delete and concurrent-edit races; exact and
conflicting retries including process restart; atomic destination sequence and
history/live projection; unsupported-peer rendering without cursor stalls;
Web/Windows cache and outbox restart tests; keyboard-accessible single- and
multi-target composition; fixed-cardinality metrics; and checks that destination
payloads and operational signals reveal no source identity or body.
