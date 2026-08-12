# ADR-0194: Atomic Windows Rollout Expansion Execution

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and operations
- Related milestone: M4
- Extends: ADR-0179, ADR-0180, ADR-0193

## Context

The dedicated expansion authorization has no mutation authority. Execution must
preserve the same pre-stage, compare-and-swap, consume-before-mutation, and
external-observation requirements as a new release without treating a
percentage change as an ordinary promotion.

## Decision

- Add a separate filesystem reference executor for rollout-expansion
  authorization. It accepts no provider credential or network operation.
- Reconstruct ADR-0193 and require current and target candidates to be their
  exact content-addressed immutable directories in the release store.
- Revalidate both live signed manifests and require current/target percentage
  and seed to equal the authorization. Compare the active pointer with the
  authorized current manifest digest and sequence before consumption.
- Write a SHA-256-addressed consumption marker under a distinct expansion
  namespace before changing the pointer. Restoring old channel state cannot
  make the authorization reusable.
- Atomically switch only `active-channel.json`, validate the resulting complete
  target, and write evidence retaining percentages, seed, health/metrics
  digests, target/rollback IDs and sequences, and authorization digest.
- Restore the exact previous pointer if post-switch validation or evidence
  persistence fails, while keeping the consumption marker.
- Mark execution `rollout-expansion-pointer-switched-awaiting-external-observation`.
  A strict public HTTPS observation and completion record remain mandatory.

## Consequences

Rollout expansion cannot overwrite an intervening release, jump from an
unexpected cohort, or replay after a partial failure. Local pointer success is
not public delivery evidence. Provider adapters must preserve equivalent
conditional mutation and durable consume-before-write semantics.

## Migration and Rollback

Pre-stage the target candidate before execution and retain the current release.
An execution failure restores the pointer automatically. After successful
execution, incident response must use evidence-derived halt/forward-fix paths,
not hand-edit the active pointer.

## Verification

- `python3 Tests/windows_update_rollout_expansion_execution_test.py`
- prove exact staged paths, stale-pointer rejection, consume-once behavior,
  pointer restoration after evidence failure, and evidence tamper rejection.
