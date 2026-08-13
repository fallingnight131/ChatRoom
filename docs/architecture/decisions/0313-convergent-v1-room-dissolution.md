# ADR-0313: Convergent V1 Room Dissolution

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3

## Context

V1 administrators can send `DELETE_ROOM_REQ`. The C++ path broadcasts deletion
before its database delete succeeds, hard-deletes room history, and attempts
object deletion without a durable retry marker. A failed database or provider
operation can therefore leave clients and durable state contradictory.

## Decision

- Bind the actor to the authenticated connection and ignore client-supplied
  room names. Only an enabled, active mapped OWNER or ADMIN may dissolve an open
  mapped GROUP.
- Model deletion as canonical dissolution, not physical erasure. In one
  serializable transaction, close the group lifecycle, end every active
  membership, remove its join credential, revoke eligible attachments into the
  existing durable object-cleanup path, and retain messages/mappings for audit
  and exact compatibility translation.
- Capture the complete active mapped audience before ending memberships. Return
  that effect intent only for the first committed dissolution; retries by the
  same actor return the original authoritative room name with `changed=false`.
- Persist a dissolution operation record so a retry can be distinguished from
  an unrelated request against an already closed room. Other callers see a
  non-enumerating rejection.
- Send `DELETE_ROOM_RSP` and `DELETE_ROOM_NOTIFY` only after commit. Emit the
  notification once, to process-local active connections in the captured
  audience. Never advertise success before durable state exists.
- Keep the handler detached from the product listener until the wider Java
  compatibility cutover gates pass.

## Consequences

Room history remains durable but becomes inaccessible through all active-room
authorization and projection paths. Object deletion is eventually completed by
the existing bounded revoke-delete-confirm cleanup loop, so dissolution does not
block on a cloud provider. Rollback can reopen canonical state only through an
explicit operator repair; ordinary clients cannot resurrect a dissolved room.

## Verification

- application tests cover input bounds, actor binding, first/retry identity,
  and audience integrity;
- PostgreSQL tests cover admin authorization, atomic lifecycle/membership/
  credential/attachment state, exact retry, competing callers, and rollback;
- gateway tests cover strict decoding, compatible UUID-free output, first-only
  notification, malformed input, saturation, and replacement-login recovery.
