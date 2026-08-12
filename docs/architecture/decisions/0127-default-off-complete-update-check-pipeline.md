# ADR-0127: Default-Off Complete Update Check Pipeline

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The client has isolated primitives for bounded manifest discovery, signed
manifest acceptance, installer transfer, and installer trust. Leaving their
composition to a future window or UI makes it possible to download Setup before
signature/policy acceptance or to expose an untrusted file as ready.

## Decision

- Add one inactive application service that owns the complete pre-launch order:
  ADR-0126 fetch, ADR-0122 signature/state/policy acceptance, then ADR-0124
  bounded download and background installer trust.
- Permit installer network I/O only for a signed `Eligible` decision. Map
  `NoUpdate`, `ManualUpdateRequired`, and `DeferredByRollout` to explicit
  outcomes without an installer request. Normalize all trust/transport failures
  to rejection and preserve cancellation.
- Reject concurrent checks and forward bounded installer progress only after
  eligibility. Expose a path only for the preparation service's `Ready` result,
  together with the signed target version.
- Keep production construction absent. Do not add a trusted key, endpoint,
  AppData/state/staging path, scheduler, UI, consent, launcher, or restart.

## Consequences

A future product adapter gets one safe pre-launch entry point instead of wiring
security-sensitive primitives itself. Invalid manifest trust and staged
deferral cannot download executable bytes, and only Authenticode-verified bytes
can be reported ready.

The ready path is a short-lived handoff whose caller must either launch through
the future consent/shutdown boundary or delete it. Launch, process exit waiting,
installer result observation, rollback UX, key custody, and public channel
operations remain separate M4 release gates.

## Migration and Rollback

No product path invokes the service and no persistent/protocol schema changes.
Rollback removes the composition and its test while retaining the independent
primitives. Activation requires a separate ADR covering product configuration,
keys, scheduling, consent, and operational rollback.

## Verification

- valid ephemeral trust fetches manifest, signature, then installer and returns
  only the integrity/trust-approved file plus signed target version;
- a modified signature fetches no installer and creates no staged executable;
- zero-percent rollout fetches no installer and reports deferred;
- a concurrent start is refused;
- the full Qt gate compiles the inactive service into the client target.
