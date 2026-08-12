# ADR-0303: Import V1 attachment messages in the ordered message transaction

- Status: Accepted
- Date: 2026-08-13

## Context

V1 text and attachment messages share one per-conversation sequence space. The
existing text importer intentionally blocked attachment payloads. Running a
separate attachment importer later would either collide with preserved sequence
entries or force attachment messages outside their original order.

## Decision

Keep the text-only import capability strict, but let payload planning explicitly
defer safe typed attachment identities. A new unified capability may accept that
plan only when every deferred record exactly matches:

- one verified attachment file/message candidate;
- one message-state row with the same conversation, sender, timestamp and recall
  state;
- the deterministic canonical message identity; and
- the same physically verified whole-file backup proof used by all three inputs.

The future target writer will consume this unified capability and write text and
attachment messages in one serializable transaction using their shared creation
and mutation sequences. It must reverify all three inputs before commit. The
existing text importer continues to require zero deferred attachments.

## Consequences

- Mixed histories preserve their original ordered cursor rather than appending
  files in a second pass.
- No legacy attachment body, filename, thumbnail, path, or URL enters the text
  payload model; the verified attachment plan remains its sole metadata source.
- Old text-only operational commands fail closed on mixed datasets until a
  dedicated unified preview/apply command is introduced.
