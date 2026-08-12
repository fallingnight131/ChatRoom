# ADR-0121: Default-Off Update Device and Replay State

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0119 defines deterministic staged rollout and per-channel manifest replay
watermarks, but intentionally leaves persistence to the caller. Regenerating a
device identifier reshuffles a user on every launch; losing or resetting an
accepted sequence permits a still-valid signed older manifest to roll policy
back. Account identity is unsuitable because it makes rollout correlate users
and changes when accounts switch.

This state must be durable and fail closed before any updater service is wired,
without entering the chat SQLite schema or making the current product perform
network/update work.

## Decision

- Add an inactive repository for one local schema-1 JSON document containing a
  random UUIDv4 device identifier and exactly stable/beta replay entries.
- Store one highest accepted sequence plus canonical-manifest SHA-256 per
  channel, independent of signing key. Reject lower sequence and same-sequence
  digest conflict; accept an identical pair idempotently.
- Generate the device identifier only when no state document exists. Never
  silently regenerate malformed, unknown-schema, partially missing, unsafe, or
  unreadable state.
- Require an absolute, non-symbolic dedicated directory and restrict it and the
  state document to owner access where the host permits. Use `QLockFile` to
  serialize processes and `QSaveFile` without direct-write fallback for atomic
  replacement.
- Keep update state separate from account/chat data. A future Windows
  application service chooses the product AppData path and passes loaded values
  to ADR-0119; this repository has no platform-path, network, key, UI, or launch
  responsibility.

## Consequences

Rollout membership remains stable across launches and accounts, and a signing-
key rotation cannot reset replay protection. A corrupted or deleted document
blocks automatic update until a deliberate recovery policy runs; it never
quietly creates a weaker high-watermark. User profile loss, uninstall data
removal, or administrator repair still needs an explicit operational policy.

The state is not a secret or account identifier, but owner-only permissions
reduce casual tampering. Filesystem atomicity and the process lock prevent
ordinary partial writes and concurrent lost updates; they do not defend against
a user or attacker who already controls the account.

## Migration and Rollback

No product state is created because no application path instantiates the
repository. Rollback removes the class and test. Once activated, schema changes
must use expand-migrate-contract and preserve device identity and accepted
watermarks; resetting them requires an explicit recovery/security decision.

## Verification

- Qt tests create and reload a stable UUIDv4 and empty stable/beta entries;
- accepted state survives restart, identical retry is idempotent, and replay or
  same-sequence conflict is rejected;
- a higher sequence replaces the watermark atomically;
- malformed durable state fails closed instead of regenerating identity.
