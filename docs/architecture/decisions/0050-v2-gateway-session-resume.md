# ADR-0050: V2 Gateway Session Resume

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The application and PostgreSQL layers can atomically rotate session proofs, but
the gateway previously rejected every `ResumeSession`. Resume must obey the same
connection state, bounded execution, deadline, identity binding, safe telemetry,
and secret-lifecycle controls as fresh password authentication.

## Decision

- Accept `ResumeSession` only after successful V2 negotiation and while the
  connection expects authentication. Bound and parse the payload, require a
  32-byte proof, and parse the session UUID without treating it as authority.
- Use only gateway and resolved-direct-peer admission dimensions for resume.
  Do not manufacture an account key before PostgreSQL has authenticated the
  session owner.
- Copy the proof into an owned application command, clear the temporary mutable
  copy, and execute the resume use case on the same bounded authentication worker
  pool. Worker saturation, timeout, internal failure, and completion telemetry
  retain the existing safe behavior.
- Map invalid UUID, invalid proof length, unknown, wrong, expired, revoked,
  device-mismatched, and replayed sessions to the identical generic
  `AuthenticationRejected(REJECTED)` response. Malformed Protobuf remains a
  protocol payload error because no typed authentication command exists.
- On success, return `SessionEstablished` with the newly rotated one-time token
  and bind the PostgreSQL-returned account/device/session identity to the channel.
  Later envelope session IDs are consistency checks only.

## Consequences

- A Web or Windows client can reconnect without resending a password while every
  successful reconnect invalidates its previous proof.
- If the server commits rotation but the response is lost, the client holding
  only the old proof must perform fresh login. A future client-side durable
  handoff/recovery design may improve this without weakening replay prevention.
- The handler remains unconnected to a listener; V1 authority is unchanged.

## Verification

Embedded-channel tests verify use-case dispatch without password authentication,
rotated-token response, secret cleanup, server identity binding, generic invalid
UUID/proof rejection, and resume admission without fake account keys. PostgreSQL
tests separately prove atomic rotation and replay denial.

## Rollback

Inject the generic-rejecting resume use case or remove the inactive route. Do not
restore reusable proofs. No V1 or production data path depends on this handler.
