# ADR-0055: Offline V1 Identity Migration Command

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The verified importer was only a Java API. Operators need a reproducible entry
point with safe defaults, explicit apply intent, stable output, and no database
password in process arguments or shell history. A backup proof also needs a
portable representation that can travel with the protected artifact.

## Decision

- Add a separate `migration-cli` application module. It depends inward on the
  PostgreSQL persistence adapter and is not part of the gateway listener.
- Expose four explicit commands: `backup`, `verify-backup`, `preview`, and
  `apply`. There is no implicit apply and no combined "migrate and serve" mode.
- Store backup proof in a strict versioned properties document using a new-file,
  temporary-write, reread, and atomic-move flow. Reject unknown/missing fields,
  oversized input, invalid hashes/counts/timestamps, overwrite, and artifact
  mismatch.
- Require the expected 64-character source fingerprint as a separate `apply`
  argument. Read the PostgreSQL URL/user/password only from dedicated migration
  environment variables; never accept the password as an argument.
- Validate Flyway state but do not apply schema migrations from this command.
  Schema rollout remains a separate deployment action.
- Emit only fixed status keys, counts, fingerprints, run UUIDs, and safe issue
  identifiers. Operational failures use a fixed message without paths, SQL,
  endpoints, usernames, or credentials.
- Run persistence and command tests serially against the disposable database so
  destructive test setup cannot race across Gradle tasks.

## Consequences

- Operators can create and independently reverify an identity backup, run a
  no-write preview, and perform a fingerprint-confirmed apply using the same
  implementation exercised in CI.
- The proof is non-secret metadata, but it and the database backup still belong
  in protected operational storage and not Git.
- The command reduces accidental invocation risk but does not quiesce V1,
  enable V2 traffic, or prove a production restore time objective.

## Verification

Pure command tests cover usage, generic failures, artifact creation,
no-overwrite behavior, non-sensitive output, and verification of an isolated
restored copy. The real-PostgreSQL gate runs backup, preview, explicit apply,
proof audit, and idempotent account reapply through the command boundary.

## Rollback

Remove the unused command module and proof codec. No production execution is
automatic. A failed apply transaction follows ADR-0054 and leaves V1
authoritative; retain its protected backup and proof according to the runbook.
