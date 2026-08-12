# ADR-0130: Complete Prepared Installer Handoff

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The preparation service previously emitted only the verified file path. The
external helper requires the signed size, SHA-256, and Authenticode publisher
thumbprint to perform ADR-0128 final locked re-verification. Re-reading those
values from UI state, a second manifest parse, or mutable globals could mix
attempts or weaken the exact evidence that produced `Ready`.

## Decision

- Define `PreparedInstaller` at the preparation application boundary with the
  path, signed size, raw 32-byte SHA-256, and raw 32-byte signer thumbprint.
- Populate it from the accepted decision before clearing active policy state.
  Retain it with the path while background trust runs; expose it only when trust
  returns `Verified` and all fields are structurally complete.
- On rejection, cancellation, exception, incomplete evidence, or destruction,
  delete the file and emit an empty value.
- Preserve the same value through the complete update-check service alongside
  the signed target version. Register it as a Qt metatype for future queued UI
  adapters; do not persist or activate it here.

## Consequences

The client helper adapter can build one attempt from the exact signed evidence
that passed eligibility and background trust. A mutable path alone no longer
represents readiness. ADR-0128 remains authoritative and repeats every check at
process creation because this value does not itself lock the file.

## Migration and Rollback

These APIs are inactive and have no external compatibility promise. Rollback
restores path-only signals but would require the helper adapter to be removed at
the same time. Once product activation occurs, evolve the handoff additively.

## Verification

- preparation success emits complete exact metadata and a live file;
- preparation rejection emits no path or metadata and removes staged bytes;
- the complete check preserves size/digest/path and target version;
- all full Qt tests and client Release build remain green.
