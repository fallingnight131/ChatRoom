# ADR-0040: PostgreSQL Identity and Session Adapter

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The identity application use case has outward account and session ports, while
the V2 schema already contains accounts, devices, and hashed sessions. The first
adapter must preserve V1 username compatibility, prevent raw-token persistence,
and close lookup-to-issuance authorization races before it can be connected to a
gateway.

## Decision

- Implement both ports in `persistence-postgres`; the application core remains
  free of JDBC and database row types.
- Preserve V1 exact, case-sensitive username matching. The future import copies
  the V1 username into `username_key`; any change to login identity semantics
  requires a separate compatibility migration.
- Recheck and row-lock an enabled account inside the issuance transaction after
  credential verification. An account disabled between lookup and issuance is
  rejected without creating a device or session.
- Upsert a device by `(account_id, client_device_id)`, reuse its stable UUID on
  reconnect, update last-seen/platform, and refuse issuance when that device is
  revoked. Password login does not silently un-revoke it.
- Generate 32 bytes with `SecureRandom`, store only SHA-256 in
  `device_session`, and return an owned raw-token copy once. Create the result
  before commit; any insert/commit failure rolls back and closes/zeros it.
- Generate application-side UUIDs and default sessions to 30 days. Lifetime and
  generators remain injectable for policy tests/bootstrap configuration.
- Map JDBC failures to a persistence exception without embedding SQL parameter
  values. Do not log credential/token data.

## Consequences

- Fresh login can persist restartable device/session truth once a real password
  verifier and gateway adapter are connected.
- Multiple active sessions per device are currently allowed; token rotation and
  session-family/revocation policy are part of the resume slice.
- No connection pool or production DataSource configuration is selected yet;
  bootstrap/runtime deployment remains incomplete.

## Verification

A disposable PostgreSQL 17.10 integration test verifies:

- exact-case V1-compatible account lookup;
- stable device reuse with a new session on the next issuance;
- database token bytes equal SHA-256(raw token) and never the raw token;
- Web platform persistence;
- revoked-device rejection;
- account-disabled-after-lookup rejection;
- no listener or V1 data/path change.

The full Java workspace and clean/restart migration gate pass.

## Rollback

No gateway invokes the adapter and V1 SQLite remains authoritative. Remove the
unused classes; test-only rows live only in disposable databases and no schema
rollback is needed.
