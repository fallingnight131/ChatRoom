# ADR-0169: Durable Windows Candidate Verification Time

- Status: Accepted
- Date: 2026-08-12
- Owners: Release engineering and security
- Extends: ADR-0162, ADR-0168

## Context

Protected signing intent is intentionally valid for two hours and native
signature/install observations for 24 hours. Candidate assembly must enforce
those windows. Reusing the verifier's wall clock for every later audit, however,
makes an immutable candidate unverifiable after 24 hours and prevents delayed
clean-host testing, incident analysis, or release retention. The candidate CLI
also passed a microsecond-bearing clock into an intent contract that requires an
exact UTC second, so a real workflow run could fail despite fixed-clock tests.

## Decision

- Normalize candidate CLI clocks to whole UTC seconds.
- During assembly, continue validating every retained input against the current
  time before copying or exposing output.
- Advance the candidate to schema 5 and record exact `assembledAt` in UTC.
- During independent candidate verification, reject a malformed or future
  assembly timestamp, but replay intent, signature evidence, and native install
  evidence freshness against `assembledAt` rather than audit wall time.
- Keep all byte hashes, identities, role ordering, channels, signer bindings,
  and semantic checks unchanged.

## Consequences

Freshness still limits authorization and observation at candidate creation,
while an unchanged candidate becomes a durable audit artifact. `assembledAt` is
not a new authorization source: it is written only after live input validation
inside atomic candidate assembly and is protected by the closed manifest shape.
There is still no public publication authorization.

## Migration and Rollback

Schema-4 candidates are rejected and should be regenerated from fresh protected
inputs because none were public releases. Rollback disables candidate creation;
do not weaken freshness checks or accept a caller-supplied assembly time.

## Verification

- candidate assembly with fresh fixed-second inputs succeeds;
- the unchanged candidate verifies 90 days later;
- a future or malformed `assembledAt` fails;
- real CLI calls use whole-second UTC;
- all intent, evidence, byte-tamper, and workflow policy suites remain green.
