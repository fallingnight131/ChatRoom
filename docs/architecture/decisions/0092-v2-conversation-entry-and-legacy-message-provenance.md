# ADR-0092: V2 Conversation Entries and Legacy Message Provenance

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

The current V2 `message` table represents only message creation. V1 uses one
conversation sequence for message creation, recall mutations, and room
administrator deletion events. Importing only surviving messages while setting
the V2 high watermark would create cursor positions that the V2 history model
could never explain.

V1 also did not record the sending device. The V2 message invariant requires a
sender-owned device, so assigning a Web or Windows device would invent product
provenance.

## Decision

- Add `conversation_entry` as the durable owner of every allocated V2
  conversation sequence. Initial entry kinds are `MESSAGE`,
  `MESSAGE_RECALLED`, and `MESSAGES_DELETED`.
- Backfill an entry for every existing V2 message and require future message
  rows to reference their matching entry. Message append writes both rows in
  the existing transaction.
- Add typed recall and bulk-deletion event tables keyed by their
  `conversation_entry`. Do not use an untyped event payload as the only durable
  truth.
- Extend the durable device platform constraint with `LEGACY`. A later import
  creates one deterministic, non-authenticating synthetic legacy device per V1
  sender account. `LEGACY` is migration provenance, not a supported client
  platform; handshake and fresh-login policy remain Web/Windows only.
- Allow an entry's event time to be unknown because historical V1 recalls have
  a sequence but no durable mutation timestamp. Preserve the import time
  separately and never fabricate an occurrence time.
- Do not advance imported conversation high watermarks or expose mutation/event
  entries through V2 history until the additive protocol registry and combined
  history adapter are implemented and verified.

## Consequences

Sequence identity becomes explicit and can represent retained V1 mutations and
deletions without pretending they are messages. Existing V2 text submission and
message-only history remain compatible during this expansion because no
supported client uses V2 product traffic and imported high watermarks remain
unchanged.

The schema alone does not complete message import. Payload/content-type mapping,
legacy message identity mapping, event protocol records, and atomic import still
require later slices.

## Verification and Rollback

Clean migration, restart, backfill, entry/message foreign keys, event JSON
shape, and device-platform constraints are verified on disposable PostgreSQL.
Message append tests must prove that an accepted message creates exactly one
matching entry and that idempotent retries create neither row twice.

The implemented target planner derives each legacy device UUID from the
canonical account UUID, reuses one device per historical sender, and emits the
fixed client ID `v1-history-import`. It requires every source sender to be an
imported member of the corresponding conversation before producing target
message rows. The planner still performs no database writes or authentication.

Rollback before imported events exist removes the additive event/entry tables
and restores the former device constraint. After event rows exist, rollback
requires restoring the pre-import PostgreSQL backup; contract migration is not
performed online.
