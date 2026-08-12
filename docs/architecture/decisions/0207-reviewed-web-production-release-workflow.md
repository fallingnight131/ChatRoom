# ADR-0207: Orchestrate Reviewed Web Production Promotion and Recovery

- Status: Accepted
- Date: 2026-08-13
- Owners: Web release engineering and operations
- Related milestone: M4
- Extends: ADR-0170 through ADR-0173, ADR-0204 through ADR-0206

## Context

Independent primitives existed, but no executable boundary joined an undeployed
artifact, preview verification, production approval, atomic selection,
post-switch observation, and incident rollback.

## Decision

- Add a manual two-job workflow on a dedicated Linux release-store runner.
  Fixed variables own the store, preview/production origins, and WebSocket path;
  dispatch cannot choose paths or origins.
- Technical readiness stages exact B, preserves active A, selects preview B,
  observes B static/routes and production A, and emits schema-2 evidence without
  production mutation.
- Production runs only after `web-production` approval. Since review may outlive
  freshness, regenerate the same evidence after approval before authorization.
- Redownload and revalidate B at both boundaries with read-only permissions and
  no build, provider credential, remote command, or arbitrary target.
- Consume authorization once, select B, observe production static/routes, and
  complete. On post-switch failure, execute and observe only authorized B-to-A.
- Retain technical evidence one day and production/recovery evidence 90 days.

## Consequences

The filesystem-pointer topology has an auditable release/recovery path. Other
providers need equivalent conditional adapters. Initial bootstrap, branded
browsers, staged health, and a real successful run remain separate gates.

## Verification

- `python3 Tests/web_production_release_workflow_test.py`
- all Web promotion/execution/completion/rollback suites
- a reviewed native run of `.github/workflows/m4-web-production-release.yml`
