# ADR-0191: Advisory Windows Rollout Health Gate

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering, operations, and security
- Related milestone: M4
- Extends: ADR-0182, ADR-0183, ADR-0184

## Context

Observed promotion proves public bytes at one instant but does not justify
expanding a staged rollout. Expanding on elapsed time alone can expose more
devices despite install failures or post-update crashes. Reacting to one early
failure also creates noisy rollback behavior, and pointer restoration cannot
downgrade clients that already accepted a higher sequence.

## Decision

- Add a versioned repository policy with stable/beta rollout steps, minimum
  observation duration and install outcomes, failure/crash rate limits, and
  minimum emergency event counts.
- Stable uses `1 → 5 → 25 → 50 → 100`, at least two hours and 100 install
  outcomes, at most 1% install failures and 0.5% crash-affected successful
  installs. Beta uses `10 → 25 → 50 → 100`, 30 minutes, 25 outcomes, 5%, and
  2%. Rates use ceiling basis points so rounding cannot hide a breach.
- Accept only an exact aggregate schema containing release identity, rollout
  percentage, bounded UTC window, and non-negative monotonic counters. Account,
  device, IP, token, event, stack, and free-text fields are excluded.
- Match metrics identity to observed promotion completion and the exact
  canonical update manifest. Monitoring starts no earlier than completion and
  ends no more than five minutes before the decision.
- Produce one write-once advisory result: `expand-eligible`, `hold`, `complete`,
  or `halt-recommended`. Low-volume/incomplete data is always `hold`; emergency
  breaches require at least three install failures or two crash-affected devices.
- Give this tool no mutation or authorization authority. Aggregate input
  provenance remains owned by the production observability exporter. A future
  expansion authorization must reconstruct the complete release chain and
  metrics provenance independently.

## Consequences

Expansion has a deterministic fail-closed policy without identity-bearing
release data. `halt-recommended` routes operators to ADR-0183/0184 and a higher-
sequence forward fix for already-updated clients; it is not an automatic
downgrade. Repository tests do not constitute production telemetry.

## Migration and Rollback

The tool is advisory, so introduction or removal does not mutate a channel.
Changing steps or thresholds requires a new ADR and policy version. Until a
protected expansion authorization exists, `expand-eligible` is not permission
to publish a broader manifest.

## Verification

- `python3 Tests/windows_update_rollout_health_test.py`
- reject identity mismatch, stale/short windows, inconsistent counters,
  unapproved percentages, malformed policy, mutation, and overwrite.
