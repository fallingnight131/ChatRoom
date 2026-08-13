# ADR-0314: Secure Retry-Convergent V1 Password Change

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3

## Context

Supported V1 clients send `CHANGE_PASSWORD_REQ` with the current and desired
password. The Java compatibility gateway does not yet implement it. Credential
changes are critical: plaintext must not survive request processing, a lost
success response must not turn an exact retry into a misleading failure, and
stolen sessions should not remain usable indefinitely after a password change.

## Decision

- Bind account and current session IDs to the authenticated connection. Decode
  both passwords into owned clearable UTF-8 buffers and never log, return, or
  persist plaintext.
- Permit an existing current password of 1-1024 Unicode code points for dormant
  V1 compatibility. Require the new password to contain 4-1024 code points.
- Inspect only an enabled mapped account with the exact active, unexpired current
  session. Verify either Argon2id or the temporary V1 salted-SHA representation
  with constant-cost compatibility crypto.
- If the current password matches, create a fresh policy Argon2id hash and use a
  serializable compare-and-set transaction to replace the credential. Record
  database time, append a non-secret audit row, and revoke every other active
  session while retaining the authenticated session that performed the change.
- If the current password no longer matches but the requested new password
  already matches the stored credential, treat it as an exact desired-state
  retry and return `success=true`, `changed=false`. Do not hash or mutate again.
- Return generic current-password rejection without revealing credential scheme.
  Treat session invalidation and concurrent credential changes as non-success.
- Preserve `CHANGE_PASSWORD_RSP`; additive `changed` and
  `otherSessionsRevoked` fields are safe for older readers to ignore. Never
  serialize hashes, salts, session identifiers, or canonical account IDs.

## Consequences

Successful changes immediately converge credentials to current Argon2id policy
and reduce exposure from other sessions. The initiating V1 connection stays
usable and updates its memory-only reconnect credential after the compatible
response. A caller who already knows the desired current password may receive a
successful no-op even when its supplied old value is wrong; this discloses no
capability beyond possession of the current credential and makes lost-response
retries reliable. Authentication abuse controls still apply before crypto work.

Rollback may ignore the additive audit/time fields while continuing to read the
Argon2id credential. Revoked sessions are not automatically restored.

## Verification

- application tests cover secret closure, Unicode bounds, old/new verification,
  legacy upgrade, exact retry, session denial, and compare-and-set conflict;
- PostgreSQL tests cover current-session ownership, atomic replacement/audit,
  other-session revocation, rollback, and replacement-login behavior;
- gateway tests cover strict duplicate/oversize decoding, bounded workers,
  generic rejection, saturation, no secret output, and exact retry.
