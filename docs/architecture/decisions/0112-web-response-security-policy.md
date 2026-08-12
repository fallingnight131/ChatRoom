# ADR-0112: Web Response Security Policy

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0109 records intended cache classes but does not carry a deployable response
contract. ADR-0111 makes V1 production traffic same-origin, so the release can
now define a narrow CSP instead of allowing arbitrary HTTPS/WSS connections.
Without a versioned, machine-checked policy, hosting adapters could silently
omit HSTS, weaken CSP, cache `index.html` as immutable, or publish source maps.

## Decision

- Keep one provider-neutral response contract at
  `packaging/web/response-policy.json` and bind its exact SHA-256 and size into
  schema-2 Web artifact metadata.
- Require HTTPS and one-year HSTS for the application authority. Do not claim
  `includeSubDomains` or preload until every affected domain is owned and
  verified.
- Limit scripts and network connections to self. Deny plugins, embedding, base
  URL changes, forms to other origins, camera, microphone, geolocation, payment,
  and USB. Permit data/blob images, blob media/workers, and same-origin/blob PDF
  frames needed by current product behavior.
- Temporarily permit inline styles because the current Vue UI uses dynamic
  inline positioning, progress, and theme values. Do not permit inline scripts
  or eval. Removing the style exception is future UI hardening work.
- Serve the version entrypoint with `no-store`, hashed assets with one-year
  immutable caching, and other static files with revalidation. Source maps stay
  forbidden.
- Require response identity headers carrying the Web SemVer and exact source
  revision so health/rollout checks can observe the active release.
- Fail verification when policy shape, CSP, headers, cache classes, scheme,
  source-map rule, or release identity is missing or changed. Security-policy
  changes require review of this ADR boundary rather than an untracked hosting
  edit.

## Consequences

Each Web artifact now carries a precise hosting contract and is suitable input
for an isolated deployment test. This is not evidence that a provider applied
the headers, that HTTPS certificates are valid, or that supported browsers
behave correctly. Those observations remain deployment and browser gates.

The exact-policy validator is intentionally strict. A legitimate header change
updates the policy, validator expectations, tests, and ADR together.

## Migration and Rollback

Existing undeployed schema-1 artifacts remain historical evidence but cannot
pass the new release assembly. Rollback deploys an earlier immutable site and
its matching response policy; it must not reuse a newer policy with older files.
No protocol or durable application data changes.

## Verification

- policy tests mutate HTTPS, source-map, CSP, HSTS, cache, and release identity
  fields and require fail-closed rejection;
- artifact tests bind the policy digest and `required-not-observed` status;
- CI copies the canonical policy into the undeployed artifact before generating
  its checksums and manifest.
