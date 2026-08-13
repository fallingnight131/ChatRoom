# ADR-0328: V2 Reply Reference Wire Contract

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

Reply and quote UI needs a durable relation to one authoritative message. Adding
an optional target to the existing `SubmitMessage` command would be wire
compatible but behaviorally unsafe: an older V2 server would ignore the unknown
field and accept a plain message while the sender believed it was a reply.
Embedding client-provided quote text would also allow forged attribution and
could keep displaying content after recall or deletion.

## Decision

- Permanently allocate message type 105 `SubmitReplyMessage` as a command. It
  carries a canonical conversation UUID, canonical target message UUID, bounded
  content type/content, and uses the envelope `client_message_id` for the same
  idempotency contract as ordinary submission.
- Do not reinterpret or extend the semantics of message type 100. A server that
  does not implement replies rejects type 105 explicitly instead of silently
  sending a plain message.
- Add optional `MessageReplyReference` field 10 to `MessageRecord`. Only the
  server may populate its canonical target message UUID, target conversation
  sequence, and target sender account UUID. The target sequence must be positive
  and precede the reply's sequence.
- Do not put quoted body, display-name snapshots, or mutable presentation data in
  the reference. Clients resolve visible content from authoritative conversation
  history and render unavailable/recalled/deleted state when it is absent.
- The later application boundary must prove the target belongs to the same
  conversation and is visible to the sender in the same transaction that accepts
  the reply. It must preserve the relation through idempotent retries.

## Consequences

Older clients safely ignore the additive record field. New clients receive an
explicit unsupported-type failure from older servers, so optimistic UI can roll
back without semantic corruption. Reply rendering may show an unavailable target
until its history range is synchronized; clients must not infer or cache quoted
content outside the normal message cache.

This slice defines no gateway dispatch, database column, or product UI. The
feature remains unavailable until those later slices are composed and gated.

## Verification

- Java validates canonical command/reference identities, bounded text content,
  envelope idempotency key, and target sequence ordering.
- Java, generated TypeScript, and generated C++ decode and deterministically
  re-encode one fixed `SubmitReplyMessage` golden payload.
- The permanent registry requires command envelopes for type 105.

## Rollback

Before product use, generated bindings and the unconsumed schema may be removed.
After any compatible client release, reserve type 105 and all published field
numbers permanently even if reply delivery is disabled.
