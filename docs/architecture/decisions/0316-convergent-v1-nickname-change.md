# ADR-0316: Convergent V1 Nickname Change

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M3

## Context

Supported V1 clients send `CHANGE_NICKNAME_REQ` with `data.displayName`. The
current Qt server mutates the account row and broadcasts one
`NICKNAME_CHANGE_NOTIFY` per joined room. The detached Java compatibility
gateway must preserve that observable behavior without trusting connection
memory as durable truth or emitting duplicate effects after an exact retry.

## Decision

- Bind the mutation to the authenticated canonical account. Normalize input to
  trimmed Unicode NFC, require 1-20 Unicode code points, and reject control
  characters before persistence.
- In one serializable PostgreSQL transaction, lock the enabled mapped account,
  compare the desired display name, update the account and profile timestamp,
  and append a non-secret audit row only when the value changes.
- Capture the complete active, legacy-mapped room audiences needed for the V1
  notification as post-commit effect intent. Fail closed rather than publish a
  partial audience if an active member lacks a legacy mapping.
- An exact retry returns `success=true`, `changed=false`, creates no audit row,
  and carries no notification effects.
- After commit, replace the connection's immutable authenticated identity with
  the authoritative display name, return `CHANGE_NICKNAME_RSP`, and route one
  compatible `NICKNAME_CHANGE_NOTIFY` per affected room to active recipients.
  Offline clients recover the durable name through later directory or room
  snapshot reads.

## Consequences

PostgreSQL remains the authority and a lost response is safe to retry. Room
notifications remain compatible with Qt and Web clients while delivery to
currently disconnected members is deliberately best-effort because the durable
profile is recovered by normal reads. Very large fan-out is bounded and rejected
before commit rather than producing an incomplete visible mutation.

Rollback may ignore the additive profile timestamp and audit table while
continuing to read `account.display_name`.

## Verification

- application tests cover normalization, Unicode bounds, control characters,
  persistence identity drift, and no-op effect invariants;
- PostgreSQL tests cover atomic update/audit, exact retry, mapped room audience,
  fail-closed rollback, and inactive-room exclusion;
- gateway tests cover strict decoding, authentication, bounded execution,
  compatible response/notification shape, identity refresh, and no-op retries.
