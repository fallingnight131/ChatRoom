# ADR-0085: V2 Live Message Event Contract

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

V2 clients can submit and page durable messages, but there is no permanent wire
identity for a server-initiated live message. Polling would increase latency and
database load while hiding the fan-out boundary required for a modern chat
system. A live event must remain reconcilable with sequence history because
delivery is at least once and events may be lost or observed with gaps.

## Decision

- Permanently allocate message type `104` as `MESSAGE_PUBLISHED`, with envelope
  kind `EVENT` and the existing bounded `MessageRecord` payload.
- Require an authenticated server-issued session ID and prohibit `request_id`
  and envelope `client_message_id` on this unsolicited event. The durable
  idempotency identity remains inside `MessageRecord.client_message_id`.
- Apply the same canonical UUID, positive signed sequence/time, supported UTF-8
  content, and size validation used by history records.
- Accept the event in the Web protocol client only in authenticated state. Merge
  by server/client message identity. Advance the durable cursor only for the
  exact next sequence; on a gap, retain the event and request authoritative
  history from the last contiguous cursor.
- Keep server publication inactive in this slice. The next slice will publish
  only after durable non-duplicate commit to active, authorization-established
  single-gateway subscribers. Multi-gateway routing remains M5 work.

## Consequences

The live path and recovery path now share one durable message projection and do
not depend on wall-clock ordering. Reserving the type before enabling fan-out
keeps old preview clients fail-closed on an unknown event, so coordinated preview
deployment is required. `MessageAccepted` remains the submitting command's
correlated response and is not replaced by this event.

## Verification

Java tests lock type 104 to event kind and validate standalone records. Generated
TypeScript and ephemeral C++ bindings come from the authoritative schema. Web
tests accept only uncorrelated, session-bound authenticated events, reject kind
or session confusion, deduplicate contiguous messages, and trigger history repair
without advancing over a sequence gap.

## Rollback

Stop sending type 104. Its numeric registry value remains permanently allocated
and must never be reused. Submission, acceptance, history, V1, and persistent
schemas are unchanged.
