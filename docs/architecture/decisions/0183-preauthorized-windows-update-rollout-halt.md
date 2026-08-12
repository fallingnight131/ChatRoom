# ADR-0183: Preauthorized Windows Update Rollout Halt

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and incident operations
- Related milestone: M4
- Extends: ADR-0180, ADR-0182

## Context

After an observed B promotion, incident response must stop further exposure
without accepting an arbitrary operator-supplied rollback version. Reusing the
old A manifest also has two hard limits: it must still be cryptographically
current, and clients that already persisted B's higher manifest sequence will
reject A. Therefore a pointer reversal is a rollout halt for unaffected clients,
not a claim that updated clients automatically downgrade.

## Decision

- Add a one-time incident consumer that derives the exact B→A transition only
  from immutable promotion completion and execution evidence.
- Reconstruct both complete releases, require B to remain the active pointer,
  and require A's Ed25519 manifest to be currently valid at rollback time.
- Persist a completion-SHA-addressed consumption marker before mutation, then
  atomically restore A and validate the resulting pointer.
- Emit `rollback-pointer-restored-awaiting-external-observation` evidence bound
  to completion/execution digests, both release IDs/sequences, A version/source,
  and exact UTC time.
- If evidence persistence fails after A is restored, never reactivate B. Keep
  the consumption marker and require manual evidence recovery/new incident
  authorization rather than replay.
- Define this operation as stopping further rollout and restoring the channel
  response for clients that have not accepted B. It does not lower persisted
  replay watermarks, uninstall B, or bypass installer downgrade protection.
- A true corrective release for affected clients must be a newly signed higher
  manifest sequence and normally a higher product version containing the fix.

## Consequences

Incident response cannot accidentally target an unrelated artifact and favors
user safety when audit persistence fails. Operators must act before A expires.
Already-updated devices remain on B until a forward corrective release; this is
consistent with anti-replay and downgrade protection rather than a hidden
limitation.

## Migration and Rollback

Existing channels need retained A/B candidates and ADR-0182 completion. A failed
precondition changes nothing. Once A is restored, external HTTPS observation is
required before the rollout halt is considered complete. Re-enabling B requires
a new promotion authorization; the incident consumer is not reversible by
editing evidence.

## Verification

- `python3 Tests/windows_update_release_rollback_test.py`
- prove exact derived transition, B-current requirement, consumption before
  mutation, replay refusal, A-current signature requirement, no B reactivation
  after evidence failure, and evidence tamper rejection;
- external restored-A observation and forward-fix rollout remain later gates.
