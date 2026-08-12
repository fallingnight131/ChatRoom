# ADR-0124: Default-Off Update Preparation Orchestration

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0122 safely accepts signed manifest policy, ADR-0123 downloads exact bounded
bytes, and ADR-0120 authenticates those bytes. Leaving their composition to a
future UI/network callback could download a deferred update, expose unverified
files as ready, retain a rejected executable, or run revocation checks on the
application thread.

## Decision

- Add one inactive update-preparation application service that owns the order:
  signed manifest acceptance, `Eligible` gate, bounded HTTPS download, then
  installer integrity/Authenticode trust. Only the final verified result is
  returned as `Ready`.
- Never download `Rejected`, `NoUpdate`, `ManualUpdateRequired`, or
  `DeferredByRollout` decisions. Permit only one preparation at a time.
- Run installer trust verification on a Qt worker task because Windows chain
  and revocation evaluation may block on platform/network services. Keep the
  downloaded file private while verification runs.
- Delete downloaded bytes after download failure, trust rejection, cancellation,
  verifier exception, or object destruction. Transfer ownership of the random
  verified path to the caller only for `Ready`; a future launch boundary must
  delete it after use.
- Cancellation aborts an active transfer. Native trust evaluation is not safely
  interruptible, so cancellation during verification takes effect immediately
  after the worker returns and never exposes the file.
- Inject keys, state/staging paths, network transport, and trust function at
  construction boundaries. The production client configures none of these and
  does not instantiate this service.

## Consequences

The update security primitives now form one default-off preparation pipeline,
and presentation code cannot mistake downloaded-but-untrusted bytes for a ready
installer. Slow revocation checks do not freeze the UI event loop.

This is not an automatic updater. Manifest discovery, production key embedding,
channel settings, scheduling, user consent, verified process launch, application
shutdown, install result/rollback observation, and telemetry remain separate M4
boundaries. Native Windows acceptance of a real signed Setup is still required.

## Migration and Rollback

No product path or persistent product configuration changes. Rollback removes
the orchestrator/test while retaining its independent primitives. Once enabled,
bypassing this ordering or returning a path before trust completes requires a
security ADR.

## Verification

- an ephemeral signed eligible manifest downloads exact bytes and reaches
  `Ready` only after injected trust succeeds;
- trust executes off the application thread and receives signed size/hash/
  thumbprint metadata;
- concurrent preparation is rejected and a zero-percent rollout causes no
  network request;
- trust rejection deletes staged bytes and emits no installer path.
