# ADR-0094: V1 Message Content Mapping Boundary

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

V1 stores `text`, `emoji`, `image`, `video`, and `file` rows in one table.
Attachment rows carry object/file metadata, not transferable message bytes.
V2 currently registers only bounded UTF-8 text content. V1 recall overwrites the
original body, so a retained recalled row cannot prove its former content.

## Decision

- Map V1 `text` and `emoji` to V2 UTF-8 text content type 1 when the retained
  body contains 1..65,536 UTF-8 bytes. Presentation may still render emoji
  idiomatically; storage does not need a separate semantic type.
- Generate target message UUIDs and sender-scoped import idempotency keys
  deterministically from the typed V1 message identity.
- Preserve the retained recall placeholder only as non-original historical
  storage and mark that original content is unavailable. The recall entry, not
  this placeholder, is authoritative presentation state.
- Block `image`, `video`, and `file` rows until a reviewed V2 attachment
  metadata registry and object authorization model exist. Never copy attachment
  bytes or Base64 thumbnails into the normal V2 message payload.
- Block unknown/system content and oversize text instead of silently coercing
  it. Import issue output contains IDs/codes only and never message content,
  filenames, thumbnails, or clear reasons.

## Consequences

Text-like history now has an explicit deterministic mapping, while attachments
remain a visible migration blocker rather than being lost or mislabeled. This
planner is pre-write only; it does not yet read bodies from SQLite, create
synthetic legacy devices, or insert target messages.

## Verification and Rollback

Unit tests cover namespace-separated deterministic UUIDs, input-order
independence, recalled-content provenance, UTF-8 bounds, attachment/unknown
blocking, duplicate source IDs, and non-disclosing issue output. Rollback
removes this read-only planner without changing either database.
