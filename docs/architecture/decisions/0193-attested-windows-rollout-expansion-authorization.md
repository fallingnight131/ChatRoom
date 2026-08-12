# ADR-0193: Attested Windows Rollout Expansion Authorization

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering, operations, and security
- Related milestone: M4
- Extends: ADR-0182, ADR-0191, ADR-0192

## Context

The health decision is advisory and general promotion now rejects percentage
changes. A dedicated authorization must prove that the current release really
completed promotion, the aggregate metrics came from the reviewed exporter,
and the proposed signed manifest is exactly the next healthy rollout step.

## Decision

- Add a write-once, 60–900-second rollout-expansion authorization for the
  `windows-update-production` environment. It has no execution or provider
  mutation logic.
- Reconstruct the complete promotion completion from its authorization,
  execution, current/rollback candidates, prior-manifest snapshot, and HTTPS
  observation. Reverify the exact current update candidate and health decision.
- Require aggregate metrics to be canonical JSON and carry a detached Ed25519
  signature. Accept only a reviewed public-key ID and exact PEM SHA-256; inspect
  the key as canonical Ed25519 SPKI and invoke only public verification.
- Reverify the complete target update candidate and require it to be assembled
  within 24 hours with a currently valid signed manifest.
- Require the target to retain the exact signed Windows candidate, installer
  metadata, update signing key, minimum-updatable version, and rollout seed.
  Only manifest validity/sequence and the policy-approved next percentage may
  change; sequence must strictly advance and no percentage step may be skipped.
- Bind current/target manifest and candidate digests, Windows candidate digest,
  metrics/key/signature, health/completion evidence, publisher and update-key
  identities, percentages, seed, version, source, and expiry.

## Consequences

A signed metrics snapshot cannot authorize a different binary, cohort seed, or
percentage jump, and a health result cannot be reused after five minutes.
Keeping the rollout seed preserves cohort monotonicity: devices eligible at a
smaller percentage stay eligible at the next step. Private exporter keys,
provider credentials, endpoint writes, and manifest signing remain outside the
tool. The authorization is not executable until the channel executor and
completion verifier explicitly support this authorization type.

## Migration and Rollback

This is additive and does not alter a live channel. Delete unused authorization
records to roll back before executor integration. Never route rollout changes
back through general promotion authorization or accept noncanonical/unsigned
aggregate metrics.

## Verification

- `python3 Tests/windows_update_rollout_health_test.py`
- `python3 Tests/windows_update_rollout_expansion_authorization_test.py`
- reject skipped percentages, changed seeds/binaries/installers, bad metrics
  signatures, stale health, expired authorization, and target drift.
