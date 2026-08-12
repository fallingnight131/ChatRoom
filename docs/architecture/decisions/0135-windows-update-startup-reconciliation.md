# ADR-0135: Windows Update Startup Reconciliation

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The launcher writes its result after the old client exits and restarts the
installed executable only after Setup returns zero. The restarted client must
reconcile that evidence before login. Starting another client while a recent
request has no result can reacquire the liveness mutex and cause NSIS to reject
an otherwise active update.

## Decision

- Derive lifecycle, result, run, and staging directories below the application's
  `QStandardPaths::AppLocalDataLocation/updates` root. Keep each concern in a
  distinct child directory.
- Add a startup application service above ADR-0133. It classifies no state,
  recent pending, stale pending, installed, failed, and rejected evidence.
- Treat a missing result as active for 20 minutes from the pending UTC time,
  with at most five minutes of future clock skew. During that window, the
  Windows entry point explains that update work is continuing and exits before
  login so it cannot block Setup. An older pending record warns but does not
  create a permanent startup lockout.
- Accept `installed` only when the running binary's canonical version equals the
  pending target version. A zero Setup exit code alone is not proof that the
  expected binary is running.
- Present successful, failed, stale, and rejected outcomes before login. Log
  normalized outcome/exit/error diagnostics without installer bytes, keys, or
  credentials. Do not expose raw technical errors in the user message.
- This activates result consumption only. Update discovery, trusted production
  keys/origin, consent, handoff, and quit remain unconfigured in the product.

## Consequences

The client now closes the post-restart half of the update lifecycle without
enabling downloads. A recent update cannot be accidentally blocked by a manual
second launch, successful outcomes are version-reconciled, and stale state does
not lock users out indefinitely.

Invalid evidence is deliberately retained by ADR-0133, so a rejected record can
warn on later launches until a future quarantine/recovery action is implemented.

## Migration and Rollback

Existing users gain empty owner-only update directories on first Windows
startup. With no pending record, behavior is unchanged. Rollback removes startup
inspection; persisted schema-1 evidence remains inert and compatible with a
later reintroduction.

## Verification

- portable filesystem tests cover empty state, recent/stale pending, matching
  installed version, installed-version mismatch, and nonzero installer failure;
- full Qt verification builds the Windows-gated entry path and all product
  components;
- native signed Setup success, restart, and visible dialogs remain Windows M4
  release evidence and are not inferred from portable tests.
