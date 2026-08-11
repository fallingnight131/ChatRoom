# ADR-0019: V1 Replayable Message Mutations

- Status: Accepted
- Date: 2026-08-11
- Owners: project maintainers
- Related milestone: M1

## Context

Room and direct messages have stable creation sequences and bounded reconnect
resume, but recall notifications exist only on the live connection. A client
that reconnects after a recall can therefore keep stale content. Reusing or
changing the original message `sequence` would break the stable-sequence
contract and make creation ordering ambiguous.

## Decision

- Keep each message's original `sequence` immutable.
- Add nullable `mutation_sequence` columns to room and direct message rows. The
  first expansion commit creates these columns and conversation-scoped indexes
  without changing runtime behavior.
- The compatible server transactionally reserves a new value from
  the existing room/friendship high watermark when a recall changes state, then
  store that value on the row.
- Sequence-mode history selects rows whose creation or mutation sequence is
  newer than the cursor, order them by `syncSequence = max(sequence,
  mutationSequence)`, and expose additive `mutationSequence` and `syncSequence`
  fields. Legacy timestamp history and the original `sequence` remain intact.
- Live recall responses and notifications expose the same mutation
  sequence. Retrying an accepted recall will return its stable result instead
  of allocating another sequence or broadcasting another mutation. File
  cleanup is an idempotent post-commit compensation and may be retried.
- Rejections and persistence failures expose stable, non-enumerating error codes
  alongside localized text.
- Web and Windows clients reconcile a repeated stable message ID as an
  authoritative state update. Older clients ignore the additive fields.
- Administrative physical deletion remains a separate event-model decision;
  this ADR does not claim deleted rows are replayable.

## Consequences

The design provides one cursor namespace per conversation without mutating
message identity or creation order. A recalled row may appear again in an
incremental history page, so clients must upsert rather than append or discard
it. SQLite retains only the latest message state, which is sufficient for V1
recovery but is not an immutable audit log.

## Verification and Rollback

The expansion is verified on first start and restart, including query-plan use
of both mutation indexes. The behavior suite covers room/direct recall, offline
replay, idempotent retry, cursor pagination, and restart durability; the common
authorization suite retains non-owner and resource-boundary coverage.

Rollback of the behavioral phase stops reading and writing mutation sequences.
The nullable columns and indexes may safely remain until a maintenance window;
no older server or client depends on their absence.
