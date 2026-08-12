# ADR-0228: Expose the V1 Contact Import Through the Offline Command

- Status: Accepted
- Date: 2026-08-13
- Owners: Contacts, migration operations, and persistence
- Related milestone: M3

## Context

The verified contact-request planner and PostgreSQL importer are intentionally
not part of a runtime route. Operators nevertheless need a repeatable way to
preview the target, confirm the independent contact fingerprint after V1 writer
quiescence, apply the exact verified input, and retain non-sensitive evidence.
The existing offline migration command already owns the physical backup proof
and identity/conversation/message sequence.

## Decision

- Add `contact-preview`, `contact-verify-final`, and `contact-apply` to the
  existing offline migration executable.
- Require the independent 64-character contact fingerprint for final verify and
  apply. Do not substitute the identity or conversation fingerprint.
- Reuse the same final whole-file SQLite backup and proof as the other slices,
  and require identity import to have established every planned account first.
- Print only status, fingerprints, aggregate pending/terminal/result counts,
  numeric V1 request IDs with fixed issue codes, and the audit run UUID. Never
  print paths, usernames, display names, or request participants.
- Document the command as a maintenance-window rehearsal only. It does not
  authorize a Java V1 route or PostgreSQL traffic authority.

## Alternatives Considered

- Add contact import to the identity `apply` command implicitly: rejected
  because the source fingerprints, target conflicts, and rollback evidence are
  independent.
- Operate the importer through an ad-hoc test or SQL script: rejected because it
  bypasses explicit fingerprint confirmation and stable safe output.
- Add an online administrative endpoint: rejected because the migration is an
  offline one-way operation and no runtime authority exists yet.

## Consequences

Operators can rehearse and audit the complete pending-request import without
activating a server route. The runbook has an explicit stop boundary for contact
graph, account, pair, target, and mapping conflicts.

## Migration and Rollback

The command changes no schema. Preview/final-verify are read-only; apply uses the
V015 atomic importer. Rollback remains restore of the reviewed pre-import
PostgreSQL backup while V1 stays authoritative.

## Verification

- target-independent final verification and wrong-fingerprint rejection;
- no sensitive source values or paths in command output;
- disposable PostgreSQL preview, first apply, exact rerun, row/map/audit counts;
- full PostgreSQL migration, migration CLI, and gateway regression gate.
