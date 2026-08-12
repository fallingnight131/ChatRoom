# ADR-0196: Bind Update Signing Key to Compiled Client Trust

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and security
- Related milestone: M4
- Extends: ADR-0176, ADR-0190

## Context

The update-channel candidate independently verified its Ed25519 manifest and
the signed Windows candidate, but did not prove that the manifest verification
PEM was one of the exact public keys compiled into `ChatClient.exe`. Both trust
domains could pass while producing an update no shipped client can accept.

## Decision

- During update-channel candidate assembly and every later verification, read
  the already closed product-trust intent from signed candidate schema 6.
- Select primary or secondary public PEM only by the manifest's exact
  `signingKeyId`.
- Require the update-manifest verification PEM bytes to equal that selected
  candidate PEM exactly. Unknown IDs, missing optional rotation PEM, links,
  changed encoding/bytes, or another valid Ed25519 key fail closed.
- Retain existing detached-signature, candidate, publisher, installer, and
  artifact closure validation. Add no private key or signing behavior.

## Consequences

A closed update candidate now proves not only that its manifest is authentic,
but that the final signed client is configured to authenticate that exact key.
This prevents an operationally valid but unusable release. Primary/secondary
overlap rotation remains supported when both keys were compiled and retained.

## Migration and Rollback

Historical test candidates with unrelated fixture keys are no longer valid.
Production candidate assembly must use the reviewed public PEM from the signed
Windows candidate trust bundle. Do not roll back to independent, unbound key
checks after product trust is enabled.

## Verification

- `python3 Tests/windows_update_channel_candidate_test.py`
- all authorization, execution, observation, halt, expansion, and rollback
  suites reconstruct the stronger candidate validation transitively.
