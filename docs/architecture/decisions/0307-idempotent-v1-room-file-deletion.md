# ADR-0307: Make V1 room-file deletion atomic and replayable

- Status: Accepted
- Date: 2026-08-13

## Context

The legacy `ROOM_FILES_DELETE_REQ` command changes message history, attachment
lifecycle, room quota, and live member views together. Performing those writes
independently can leave a deleted file visible after reconnect, release quota
without an audit event, or repeat notifications when a client retries after an
ambiguous network failure.

The Java compatibility listener is still detached from the product port, but it
needs the complete durable behavior before traffic cutover. Authorization and
operation identity cannot be trusted from client-supplied user fields.

## Decision

Bind the actor to the authenticated server session. Accept only an enabled,
mapped `OWNER` or `ADMIN` of an active mapped room. The request contains a
positive room ID, one to 100 distinct positive file IDs, and a bounded
client-operation ID. Sort the selected file IDs and derive a command fingerprint
from the room and selection.

Use `(actor_account_id, client_operation_id)` as the runtime deletion idempotency
key. An exact fingerprint match returns the original result as a duplicate. A
different fingerprint returns `CLIENT_OPERATION_ID_CONFLICT` without changing
state.

In one serializable PostgreSQL transaction:

1. lock and authorize the room and administrator;
2. resolve only complete READY room attachment/message mappings;
3. allocate the next shared conversation sequence;
4. mark resolved attachments `REVOKED`, remove their canonical messages and
   obsolete recall entries, and retain the attachment rows and V1 file mappings;
5. append one `MESSAGES_DELETED` conversation entry, deletion event, and V1
   deletion-event mapping; and
6. calculate active mapped READY bytes for the response quota.

V031 adds a descending positive V1 deletion-event identity sequence and a
partial unique index for runtime V2 operation identities. The compatibility
history completeness gate accepts only fully mapped `V1_IMPORT` or `V2`
deletion events. Other or incomplete deletion entries continue to fail closed.

Only the first committed deletion with resolved files emits process-local
`DELETE_MSGS_NOTIFY` and `ROOM_FILES_NOTIFY` to other active mapped members.
Duplicates, rejections, and empty resolutions do not notify. Responses and
notifications expose legacy numeric identities and quota values, never UUIDs or
object-storage metadata.

Object deletion is not performed inside the database transaction. Retained
`REVOKED` attachments enter the existing idempotent revoke-delete-confirm retry
path. That cleanup loop and the product listener remain inactive until their
separate external-provider and cutover gates pass.

## Consequences

- A response loss can be retried without a second sequence, audit event, quota
  mutation, or member notification.
- Reconnect history observes the deletion at the same shared sequence used by
  live notification, rather than silently skipping removed messages.
- Database truth commits before fallible local routing and external object
  cleanup; either failure cannot reinterpret a committed command.
- The migration is additive and forward-only. Code rollback may stop creating
  runtime deletion events while leaving V031 and committed audit history intact.
