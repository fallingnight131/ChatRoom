# ADR-0170: Web Production Promotion Authorization

- Status: Accepted
- Date: 2026-08-12
- Owners: Web release engineering and operations
- Extends: ADR-0145

## Context

ADR-0145 produces an independently verifiable technical-promotion record but
intentionally grants no authority to change public traffic. Allowing a hosting
adapter to treat technical readiness as business/operational approval would
collapse two controls, permit stale promotion records, and make rollback target
selection implicit. Storing provider credentials in a generic evidence tool
would further broaden the trust boundary.

## Decision

- Introduce a write-once schema-1 Web production-promotion authorization.
- Reverify the exact technical-promotion record against candidate static
  observation, route observation, immutable candidate, distinct rollback
  artifact, and rollback observation before issuing authorization.
- Bind fixed `web-production` environment, HTTPS origin, candidate and rollback
  IDs, version, source revision, and SHA-256 of the technical record.
- Require exact-second UTC approval, a 60-to-900-second authorization lifetime,
  and a technical approval no older than 15 minutes or from the future.
- Label it `production-promotion-approved-not-executed`.
- Accept no provider token, secret, endpoint mutation command, DNS/CDN account,
  or wildcard release identity. Execute no network or hosting mutation.
- Require any future provider adapter to reverify and consume this authorization
  before mutation, then produce distinct post-switch and rollback evidence.

## Consequences

Technical readiness, operational approval, and provider mutation become three
auditable boundaries. Compromise of this credential-free tool cannot directly
change production. Operators must refresh observations and approval when the
short window expires. Initial bootstrap without a previous release remains a
separate decision rather than silently bypassing rollback requirements.

## Migration and Rollback

Existing technical records remain non-publishing evidence. Do not retrofit them
as authorizations. Removing this tool changes no external state. A future
adapter must stop without mutation when authorization is absent, expired, or
does not reconstruct from its bound inputs.

## Verification

- `python3 Tests/web_release_authorization_test.py`
- reject expiry, future/stale technical approval, invalid lifetime, unknown or
  duplicate fields, source mutation, changed promotion identity, and overwrite
- keep provider credentials and mutation logic absent
