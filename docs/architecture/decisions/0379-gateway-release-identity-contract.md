# ADR-0379: Gateway Release Identity Contract

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5
- Amends: ADR-0056 operational path list

## Context

The existing rolling gate replaces a gateway with the same application classes.
It proves topology recovery, not mixed-version compatibility. A deployment
controller cannot safely start a mixed release unless it can identify the exact
artifact revision, protocol generation, and compatibility boundary reported by
each running process. Image tags and scheduler metadata alone do not prove what
the JVM actually loaded.

## Decision

- Define one immutable gateway identity containing release SemVer, exact
  lowercase 40-hex source revision, protocol version, and positive compatibility
  epoch. Protocol version comes from the running V2 protocol module and cannot
  be overridden by environment.
- Require release version and source revision to be supplied together. Omitted
  values produce the honest local identity `development`/`unknown`; a partial or
  malformed production identity fails configuration before listener bind.
- Default compatibility epoch to `1`. Changing its meaning or value requires an
  ADR and a migration/rollback plan; it is not a substitute for protocol tests.
- Add exact GET-only loopback admin path `/identity`. Return a versioned,
  deterministic JSON object with `no-store` and `nosniff`; reject queries,
  suffixes, and mutation methods through the existing admin response policy.
- Keep identity off the public product listener and out of user-facing protocol
  payloads. Deployment tooling must compare it through node-local access.

## Consequences

Operators and future mixed-version gates can bind observations to the running
artifact instead of trusting a mutable tag. Development remains convenient but
cannot accidentally masquerade as a release revision.

This does not yet prove that two releases are compatible, automatically block a
rollout, validate signed artifacts, or correlate identity across multiple edge
nodes. Those require a follow-up gate using two independently built revisions.

## Verification

Unit tests cover paired configuration, strict SemVer/revision/epoch validation,
runtime protocol binding, deterministic JSON, exact-path behavior, content type,
and cache/security headers. The complete Java check remains the regression gate.

## Rollback

Remove the identity value, configuration parsing, and loopback endpoint. Existing
health, metrics, product protocol, database schema, and client behavior remain
unchanged. Rollout automation must stop depending on `/identity` before rollback.
