# ADR-0321: User-Managed Device Revocation

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

V2 already gives each Web/Windows installation a durable device row and issues
multiple expiring, rotatable sessions. Users cannot yet see those devices or
invalidate a lost installation. Updating `device.revoked_at` alone would remove
future access but would not retain which authenticated device/session performed
the security action.

## Decision

- Add a bounded, server-authoritative active-device directory showing platform,
  creation/last-seen time, and which row is the current authenticated device.
  Never expose resume tokens, token digests, client IP history, or another
  account's device identifier.
- Bind every command to the authenticated account, device, and session held by
  the gateway. Before listing or mutating, PostgreSQL must prove that account,
  current device, and current session are still active and mutually owned.
- Permit this command to revoke only another active device belonging to the same
  account. Current-device logout remains a separate future operation so a UI
  mistake cannot silently destroy its only recovery context.
- In one serializable transaction, database-time stamp the target device,
  revoke all of its still-active sessions, and persist one immutable audit row
  containing the actor device/session and affected-session count.
- Treat an exact retry for the same already-revoked target as success without a
  second audit row. Missing, foreign-account, current-device, stale-actor, and
  malformed targets use a generic rejection at the protocol boundary.
- After commit, the gateway may close a process-local target connection. Durable
  revocation is authoritative; multi-gateway disconnect fan-out belongs to M5
  routing and is not required for security because every resumed/new command
  must recheck durable admission where applicable.

## Consequences

V043 adds `device_revocation_audit` and keeps existing device/session rows for
message provenance and security review. Revoked devices cannot be silently
reactivated by login because the existing device upsert already refuses them.
A future explicit “trust this device again” flow requires a separate decision
and fresh credential proof.

This foundation does not change V1 behavior and is detached until V2 protocol,
gateway, and supported-client UI slices are implemented. Rolling back the new
code leaves an additive unused table and the current login/session path intact.

## Verification

- migrate clean and existing databases, validate restart and constraints;
- list only the actor's bounded active devices and identify the current device;
- revoke another device plus all active sessions atomically using database time;
- prove exact retry creates no second audit, current/foreign targets are denied,
  and a stale or revoked actor cannot mutate;
- race two devices attempting to revoke each other and require at most one
  successful authority path after retry;
- prove target resume/new operations are denied after process restart.
