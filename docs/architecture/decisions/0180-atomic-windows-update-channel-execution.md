# ADR-0180: Atomic Windows Update-Channel Execution

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and operations
- Related milestone: M4
- Extends: ADR-0178, ADR-0179

## Context

An immutable staged release and short-lived authorization still need a narrowly
scoped mutation boundary. Activating without compare-and-swap could overwrite
an intervening release; marking authorization consumed after mutation permits
replay after a crash. Publishing multiple files directly also risks partial
visibility and weak rollback provenance.

## Decision

- Add a provider-neutral filesystem execution adapter for an existing Windows
  stable or beta channel. It accepts no network or provider credentials.
- Reconstruct the short-lived ADR-0178 authorization and both the target and
  currently active complete immutable ADR-0179 releases.
- Require the target candidate path to be its exact content-addressed store
  directory. Require the closed active pointer and its canonical manifest bytes
  to equal the authorization's expected-current sequence and SHA-256.
- Persist a SHA-256-addressed, write-once authorization consumption marker
  before mutation so crashes and retries fail closed rather than replaying.
- Atomically replace only `active-channel.json`, validate that it resolves to
  the target release, then write closed execution evidence with status
  `channel-pointer-switched-awaiting-external-observation`.
- If pointer validation or evidence persistence fails after switching, restore
  the exact previous pointer. Keep the consumption marker, requiring a fresh
  authorization for another attempt.
- Derive and retain the exact rollback release ID and sequence from the prior
  complete candidate. Treat pointer execution as pending until an external
  HTTPS observer proves the fixed manifest, signature, and Setup URLs.
- Initial empty-channel bootstrap remains outside this adapter.

## Consequences

The local/provider-equivalent activation unit is one small atomic pointer, with
all release bytes already immutable and complete. Stale state, retries, and
evidence failures cannot silently advance the channel. Provider adapters must
offer equivalent conditional mutation and durability; local success alone does
not prove CDN or client-visible state.

## Migration and Rollback

Existing channels need one retained complete release and a valid active pointer
before using this path. Immediate execution failure restores that pointer. A
successful switch is not rolled back through ad hoc input; a later incident
consumer must derive the exact reverse transition from execution evidence.

## Verification

- `python3 Tests/windows_update_release_execution_test.py`
- prove consumption-before-mutation, exact compare-and-swap, atomic switch,
  retry refusal, failure restoration, pointer/evidence tamper rejection, and
  unsafe-boundary rejection;
- require external HTTPS observation before publication completion.
